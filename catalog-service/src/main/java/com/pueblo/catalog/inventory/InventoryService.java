package com.pueblo.catalog.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.messaging.EventEnvelope;
import com.pueblo.messaging.JdbcOutbox;
import com.pueblo.messaging.OutboxRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static com.pueblo.catalog.inventory.InventoryCommand.Type.*;

/** All writes enter a fresh service-local transaction; no remote call occurs inside it. */
public final class InventoryService {
    public static final String EVENTS_TOPIC = "pueblo.inventory.events.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JdbcOutbox outbox;
    private final TransactionTemplate transaction;

    public InventoryService(JdbcTemplate jdbc, ObjectMapper mapper, JdbcOutbox outbox, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper; this.outbox = outbox;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public InventoryResult execute(InventoryCommand command) {
        Objects.requireNonNull(command);
        for (int attempt = 0; ; attempt++) {
            try { return transaction.execute(tx -> process(command, tx)); }
            catch (PessimisticLockingFailureException ex) {
                if (attempt == 2) throw ex; // Whole transaction retry, never retry an individual decrement.
                try { Thread.sleep(20L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("Inventory retry interrupted");
                }
            }
        }
    }

    private InventoryResult process(InventoryCommand c, TransactionStatus tx) {
        String bodyHash = hash(json(c));
        jdbc.update("INSERT INTO inventory_command(command_id,body_hash) VALUES(?,?) ON DUPLICATE KEY UPDATE command_id=command_id",
                c.commandId().toString(), bodyHash);
        var receipt = jdbc.queryForMap("SELECT body_hash,result FROM inventory_command WHERE command_id=? FOR UPDATE", c.commandId().toString());
        if (!bodyHash.equals(receipt.get("body_hash"))) throw new IllegalArgumentException("Inventory command ID reused with another body");
        if (receipt.get("result") != null) return readResult(receipt.get("result").toString());

        String orderId = c.orderId().toString();
        jdbc.update("INSERT INTO inventory_order(order_id) VALUES(?) ON DUPLICATE KEY UPDATE order_id=order_id", orderId);
        var order = jdbc.queryForMap("SELECT generation,reservation_id,version FROM inventory_order WHERE order_id=? FOR UPDATE", orderId);
        int latest = ((Number) order.get("generation")).intValue();
        long version = ((Number) order.get("version")).longValue() + 1;
        Reservation current = reservation(c.reservationId().toString());
        String reason = null;
        if (current != null && (!current.orderId.equals(orderId) || current.generation != c.generation())) {
            throw new IllegalArgumentException("Reservation identity belongs to another order or generation");
        }
        if (c.generation() < latest) reason = "STALE_GENERATION";
        else if (c.generation() == latest && !c.reservationId().toString().equals(order.get("reservation_id"))) reason = "RESERVATION_ID_CONFLICT";
        else if (c.generation() > latest) {
            Reservation previous = order.get("reservation_id") == null ? null : reservation(order.get("reservation_id").toString());
            if (previous != null && List.of("RESERVED", "COMMITTED").contains(previous.state)) reason = "ACTIVE_GENERATION";
            else if (c.type() == RESERVE || c.type() == REACQUIRE || c.type() == RELEASE) {
                jdbc.update("UPDATE inventory_order SET generation=?,reservation_id=? WHERE order_id=?",
                        c.generation(), c.reservationId().toString(), orderId);
            } else reason = "UNKNOWN_RESERVATION";
        }

        if (reason == null) {
            switch (c.type()) {
                case RESERVE, REACQUIRE -> reason = reserve(c, current, tx);
                case RELEASE -> {
                    if (current == null) insert(c, "RELEASED", null); // Cancellation tombstone before reserve arrives.
                    else if (current.state.equals("RESERVED")) release(current, "RELEASED");
                    else if (!List.of("RELEASED", "EXPIRED", "REJECTED").contains(current.state)) reason = "INVALID_STATE";
                }
                case COMMIT -> {
                    if (current == null) reason = "UNKNOWN_RESERVATION";
                    else if (current.state.equals("RESERVED")) {
                        if (!current.expiresAt.isAfter(now())) { release(current, "EXPIRED"); reason = "RESERVATION_EXPIRED"; }
                        else state(current.id, "COMMITTED");
                    } else if (!current.state.equals("COMMITTED")) reason = "INVALID_STATE";
                }
                case RESTORE -> {
                    if (current == null) reason = "UNKNOWN_RESERVATION";
                    else if (current.state.equals("COMMITTED")) {
                        // The order service may send this only after confirmed full refund before shipment.
                        jdbc.update("UPDATE inventory_reservation SET refund_id=? WHERE id=?", c.refundId().toString(), current.id);
                        release(current, "RESTORED");
                    } else if (!current.state.equals("RESTORED") || !c.refundId().toString().equals(current.refundId)) reason = "INVALID_STATE";
                }
                case EXPIRE -> {
                    if (current == null) reason = "UNKNOWN_RESERVATION";
                    else if (current.state.equals("RESERVED") && !current.expiresAt.isAfter(now())) release(current, "EXPIRED");
                    else reason = "NOT_EXPIRABLE";
                }
            }
        }
        Reservation updated = reservation(c.reservationId().toString());
        String eventType = reason != null ? "InventoryCommandRejected" : switch (c.type()) {
            case RESERVE, REACQUIRE -> "InventoryReserved";
            case RELEASE -> "InventoryReleased";
            case COMMIT -> "InventoryCommitted";
            case RESTORE -> "InventoryRestored";
            case EXPIRE -> "InventoryExpired";
        };
        InventoryResult result = new InventoryResult(c.commandId(), c.orderId(), c.reservationId(), c.generation(),
                eventType, updated == null ? "ABSENT" : updated.state, reason,
                updated == null ? null : updated.reservedAt, updated == null ? null : updated.expiresAt, version);
        jdbc.update("UPDATE inventory_order SET version=? WHERE order_id=?", version, orderId);
        jdbc.update("UPDATE inventory_command SET result=? WHERE command_id=?", json(result), c.commandId().toString());
        outbox.append(new OutboxRecord(EVENTS_TOPIC, orderId, new EventEnvelope(UUID.randomUUID(), eventType, 1,
                "catalog-service", orderId, version, now(), orderId, c.commandId().toString(), null, mapper.valueToTree(result))));
        return result;
    }

    private String reserve(InventoryCommand c, Reservation current, TransactionStatus tx) {
        String itemsHash = hash(json(c.items()));
        if (current != null) {
            if (current.itemsHash != null && !itemsHash.equals(current.itemsHash)) return "RESERVATION_PAYLOAD_CONFLICT";
            if (current.state.equals("RESERVED") && !current.expiresAt.isAfter(now())) { release(current, "EXPIRED"); return "RESERVATION_EXPIRED"; }
            return current.state.equals("RESERVED") ? null : "INVALID_STATE";
        }
        insert(c, "REJECTED", itemsHash);
        Instant before = now();
        if (!c.commandExpiresAt().isAfter(before)) return "COMMAND_EXPIRED";
        if (c.commandExpiresAt().isAfter(before.plusSeconds(30))) return "COMMAND_DEADLINE_TOO_FAR";
        Object savepoint = tx.createSavepoint();
        String failure = null;
        for (var item : c.items()) {
            int changed = jdbc.update("""
                    UPDATE catalog_sku s JOIN catalog_product p ON p.id=s.product_id JOIN catalog_brand b ON b.id=p.brand_id
                    SET s.stock=s.stock-?
                    WHERE s.id=? AND s.stock>=? AND s.purchase_limit>=? AND s.active=TRUE AND p.status='PUBLISHED' AND b.active=TRUE
                      AND (? IS NULL OR COALESCE(s.price_override,p.sale_price)=?)
                    """, item.quantity(), item.skuId().toString(), item.quantity(), item.quantity(), item.expectedUnitPrice(), item.expectedUnitPrice());
            if (changed != 1) {
                var prices = jdbc.queryForList("SELECT COALESCE(s.price_override,p.sale_price) FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id WHERE s.id=?", Long.class, item.skuId().toString());
                failure = item.expectedUnitPrice() != null && !prices.isEmpty() && !prices.getFirst().equals(item.expectedUnitPrice()) ? "PRICE_CHANGED" : "INVENTORY_UNAVAILABLE";
                break;
            }
            jdbc.update("INSERT INTO inventory_reservation_item(reservation_id,sku_id,quantity) VALUES(?,?,?)",
                    c.reservationId().toString(), item.skuId().toString(), item.quantity());
        }
        Instant reservedAt = now();
        if (!c.commandExpiresAt().isAfter(reservedAt)) failure = "COMMAND_EXPIRED";
        if (failure != null) tx.rollbackToSavepoint(savepoint);
        tx.releaseSavepoint(savepoint);
        if (failure == null) jdbc.update("UPDATE inventory_reservation SET status='RESERVED',reserved_at=?,expires_at=?,updated_at=? WHERE id=?",
                Timestamp.from(reservedAt), Timestamp.from(reservedAt.plusSeconds(900)), Timestamp.from(reservedAt), c.reservationId().toString());
        return failure;
    }

    private void insert(InventoryCommand c, String state, String hash) {
        jdbc.update("INSERT INTO inventory_reservation(id,order_id,generation,status,items_hash) VALUES(?,?,?,?,?)",
                c.reservationId().toString(), c.orderId().toString(), c.generation(), state, hash);
    }

    private void release(Reservation reservation, String state) {
        var items = jdbc.query("SELECT sku_id,quantity FROM inventory_reservation_item WHERE reservation_id=? ORDER BY sku_id",
                (r, n) -> new InventoryCommand.Item(UUID.fromString(r.getString(1)), r.getInt(2)), reservation.id);
        for (var item : items) {
            int changed = jdbc.update("UPDATE catalog_sku SET stock=stock+? WHERE id=? AND stock<=2147483647-?",
                    item.quantity(), item.skuId().toString(), item.quantity());
            if (changed != 1) throw new IllegalStateException("Inventory restoration requires operator reconciliation");
        }
        state(reservation.id, state);
    }

    private void state(String id, String state) {
        jdbc.update("UPDATE inventory_reservation SET status=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?", state, id);
    }
    private record Reservation(String id, String orderId, int generation, String state, String itemsHash,
            Instant reservedAt, Instant expiresAt, String refundId) { }
    private Reservation reservation(String id) {
        return jdbc.query("SELECT * FROM inventory_reservation WHERE id=?", (r, n) -> new Reservation(r.getString("id"),
                r.getString("order_id"), r.getInt("generation"), r.getString("status"), r.getString("items_hash"),
                r.getTimestamp("reserved_at") == null ? null : r.getTimestamp("reserved_at").toInstant(),
                r.getTimestamp("expires_at") == null ? null : r.getTimestamp("expires_at").toInstant(), r.getString("refund_id")), id)
                .stream().findFirst().orElse(null);
    }
    public int expireBatch() {
        var commands = jdbc.query("SELECT id,order_id,generation FROM inventory_reservation WHERE status='RESERVED' AND expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 100",
                (r, n) -> new InventoryCommand(UUID.nameUUIDFromBytes(("expiry:" + r.getString(1)).getBytes(StandardCharsets.UTF_8)),
                        EXPIRE, UUID.fromString(r.getString(2)), UUID.fromString(r.getString(1)), r.getInt(3), List.of(), null, null));
        commands.forEach(this::execute);
        return commands.size();
    }
    private Instant now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Timestamp.class).toInstant(); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid inventory data", ex); }
    }
    private InventoryResult readResult(String value) {
        try { return mapper.readValue(value, InventoryResult.class); }
        catch (Exception ex) { throw new IllegalStateException("Invalid stored inventory result", ex); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
