package com.pueblo.order.checkout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import com.pueblo.common.exception.ErrorCode;
import com.pueblo.messaging.*;
import com.pueblo.order.exception.OrderErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.pueblo.order.checkout.OrderModels.*;

public final class OrderService {
    public static final String COMMANDS_TOPIC = "pueblo.inventory.commands.v1";
    public static final String RESULTS_TOPIC = "pueblo.inventory.events.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JdbcOutbox outbox;
    private final ServiceClients clients;
    private final TransactionTemplate tx;
    private final String termsVersion;
    public OrderService(JdbcTemplate jdbc, ObjectMapper mapper, JdbcOutbox outbox,
            ServiceClients clients, PlatformTransactionManager manager, String termsVersion) {
        this.jdbc=jdbc; this.mapper=mapper; this.outbox=outbox; this.clients=clients; this.termsVersion=termsVersion;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public Quote quote(UUID user, QuoteRequest request) {
        List<Line> lines=prices(request.items()); // Network calls never hold a DB transaction.
        long total=lines.stream().mapToLong(Line::amount).reduce(0, Math::addExact);
        UUID id=UUID.randomUUID();
        Instant expiry=now().plusSeconds(300);
        jdbc.update("INSERT INTO checkout_quote(id,user_id,items,total_amount,expires_at) VALUES(?,?,?,?,?)",
                id.toString(), user.toString(), json(lines), total, Timestamp.from(expiry));
        return new Quote(id,lines,total,"KRW",expiry);
    }

    private List<Line> prices(List<Item> requested) {
        Map<String,Item> combined=new TreeMap<>();
        for (Item item:requested) {
            Item before=combined.get(item.skuId().toString());
            if(before!=null && !before.productId().equals(item.productId())) throw error(CommonErrorCode.INVALID_INPUT);
            int quantity=item.quantity()+(before==null?0:before.quantity());
            if(quantity>9) throw error(OrderErrorCode.QUANTITY_LIMIT_EXCEEDED);
            combined.put(item.skuId().toString(),new Item(item.productId(),item.skuId(),quantity));
        }
        Map<UUID,com.fasterxml.jackson.databind.JsonNode> products=new HashMap<>();
        List<Line> lines=new ArrayList<>();
        for(Item item:combined.values()) {
            var product=products.computeIfAbsent(item.productId(),clients::product);
            if(!product.path("id").asText().equals(item.productId().toString()) || !"KRW".equals(product.path("currency").asText())
                    || !"ON_SALE".equals(product.path("saleStatus").asText())) throw error(OrderErrorCode.PRODUCT_UNAVAILABLE);
            com.fasterxml.jackson.databind.JsonNode sku=null;
            for(var candidate:product.path("skus")) if(candidate.path("id").asText().equals(item.skuId().toString())) sku=candidate;
            if(sku==null || !"ON_SALE".equals(sku.path("saleStatus").asText()) || sku.path("availableQuantity").asInt(-1)<item.quantity())
                throw error(OrderErrorCode.PRODUCT_UNAVAILABLE);
            if(sku.path("purchaseLimit").asInt(-1)<item.quantity()) throw error(OrderErrorCode.QUANTITY_LIMIT_EXCEEDED);
            var price=sku.path("price");
            if(!price.isIntegralNumber() || !price.canConvertToLong() || price.longValue()<0 || price.longValue()>1_000_000_000_000L)
                throw error(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            Map<String,String> options=new TreeMap<>();
            if(!sku.path("options").isObject()) throw error(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            sku.path("options").fields().forEachRemaining(entry->options.put(entry.getKey(),entry.getValue().asText()));
            lines.add(new Line(item.productId(),item.skuId(),product.path("name").asText(),product.path("brand").path("name").asText(),
                    Map.copyOf(options),price.longValue(),item.quantity(),Math.multiplyExact(price.longValue(),item.quantity())));
        }
        return List.copyOf(lines);
    }

    public Accepted create(UUID user, String key, CreateRequest request) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw error(CommonErrorCode.INVALID_INPUT);
        String bodyHash=hash(json(request));
        Accepted previous=receipt(user,key,bodyHash,false);
        if(previous!=null) return previous;
        if(!request.agreed() || !termsVersion.equals(request.purchaseTermsVersion())) throw error(OrderErrorCode.CONSENT_REQUIRED);
        Quote quote=loadQuote(user,request.quoteId(),false);
        if(!quote.expiresAt().isAfter(now())) throw error(OrderErrorCode.QUOTE_EXPIRED);
        List<Line> refreshed=prices(quote.items().stream().map(l->new Item(l.productId(),l.skuId(),l.quantity())).toList());
        for(int i=0;i<refreshed.size();i++) if(refreshed.get(i).unitPrice()!=quote.items().get(i).unitPrice()) throw error(OrderErrorCode.PRICE_CHANGED);
        return tx.execute(status->{
            jdbc.update("INSERT INTO order_idempotency(user_id,request_key,body_hash) VALUES(?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key",
                    user.toString(),key,bodyHash);
            Accepted replay=receipt(user,key,bodyHash,true);
            if(replay!=null) return replay;
            Quote locked=loadQuote(user,request.quoteId(),true);
            Instant now=now();
            if(!locked.expiresAt().isAfter(now)) throw error(OrderErrorCode.QUOTE_EXPIRED);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_order WHERE quote_id=?)",Boolean.class,locked.quoteId().toString())))
                throw error(OrderErrorCode.QUOTE_ALREADY_USED);
            UUID id=UUID.randomUUID(), reservation=UUID.randomUUID(), reserve=UUID.randomUUID(), release=UUID.randomUUID();
            Instant deadline=now.plusSeconds(30);
            jdbc.update("""
                    INSERT INTO customer_order(id,user_id,quote_id,items,total_amount,shipping,consent,status,
                    reservation_id,reserve_command_id,release_command_id,prepare_deadline)
                    VALUES(?,?,?,?,?,?,?,'PREPARING',?,?,?,?)
                    """,id.toString(),user.toString(),locked.quoteId().toString(),json(locked.items()),locked.totalAmount(),json(request.shipping()),
                    json(Map.of("version",termsVersion,"agreedAt",now)),reservation.toString(),reserve.toString(),release.toString(),Timestamp.from(deadline));
            append(new Command(reserve,"RESERVE",id,reservation,1,locked.items().stream().map(l->new CommandItem(l.skuId(),l.quantity(),l.unitPrice())).toList(),deadline,null),now);
            Accepted result=new Accepted(id,"PREPARING",locked.totalAmount(),"KRW",deadline);
            jdbc.update("UPDATE order_idempotency SET result=? WHERE user_id=? AND request_key=?",json(result),user.toString(),key);
            return result;
        });
    }
    private Accepted receipt(UUID user,String key,String expected,boolean lock) {
        return jdbc.query("SELECT body_hash,result FROM order_idempotency WHERE user_id=? AND request_key=?"+(lock?" FOR UPDATE":""),(rs,n)->{
            if(!expected.equals(rs.getString(1))) throw error(OrderErrorCode.IDEMPOTENCY_CONFLICT);
            return rs.getString(2)==null?null:read(rs.getString(2),Accepted.class);
        },user.toString(),key).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
    private Quote loadQuote(UUID user,UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM checkout_quote WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Quote(id,lines(rs.getString("items")),
                rs.getLong("total_amount"),"KRW",rs.getTimestamp("expires_at").toInstant()),id.toString(),user.toString())
                .stream().findFirst().orElseThrow(()->error(CommonErrorCode.NOT_FOUND));
    }
    public View get(UUID user,UUID id) { return view(owned(user,id,false)); }
    public Page list(UUID user,int page,int size) {
        if(page<1 || page>100000 || size<1 || size>100) throw error(CommonErrorCode.INVALID_INPUT);
        List<Summary> items=jdbc.query("SELECT id,status,total_amount,created_at FROM customer_order WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (r,n)->new Summary(UUID.fromString(r.getString(1)),r.getString(2),r.getLong(3),"KRW",r.getTimestamp(4).toInstant()),user.toString(),size,(page-1)*size);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE user_id=?",Long.class,user.toString());
        return new Page(items,page,size,count);
    }
    public View cancel(UUID user,UUID id) {
        return tx.execute(status->{
            Row row=owned(user,id,true);
            if(Set.of("CANCELED","EXPIRED","REJECTED","ABORTING").contains(row.state)) return view(row);
            if(row.state.equals("PAYMENT_PROCESSING")) throw error(OrderErrorCode.PAYMENT_ALREADY_STARTED);
            if(!Set.of("PREPARING","PENDING_PAYMENT").contains(row.state)) throw error(OrderErrorCode.ORDER_STATE_CONFLICT);
            abort(row,"CANCELED","USER_CANCELED");
            return view(row(id,true));
        });
    }
    public int expireBatch() {
        List<String> ids=jdbc.queryForList("""
                SELECT id FROM customer_order WHERE (status='PREPARING' AND prepare_deadline<=UTC_TIMESTAMP(6))
                OR (status IN ('PENDING_PAYMENT','PAYMENT_PROCESSING') AND expires_at<=UTC_TIMESTAMP(6)) ORDER BY created_at,id LIMIT 100
                """,String.class);
        for(String id:ids) tx.executeWithoutResult(status->{
            Row row=row(UUID.fromString(id),true); Instant now=now();
            if(row.state.equals("PREPARING") && !row.deadline.isAfter(now)) abort(row,"REJECTED","RESERVATION_TIMEOUT");
            else if(row.state.equals("PENDING_PAYMENT") && !row.expires.isAfter(now)) abort(row,"EXPIRED","RESERVATION_EXPIRED");
            else if(row.state.equals("PAYMENT_PROCESSING") && !row.expires.isAfter(now)) {
                state(row,"RECONCILIATION_REQUIRED","PAYMENT_STATUS_UNKNOWN");
                release(row); // Reservation expiry is not evidence that the PG charge failed.
            }
        });
        return ids.size();
    }

    /** Authenticated transport calls this; receipt, transition and follow-up command commit together. */
    public void result(EventEnvelope envelope, Result result) {
        String bodyHash=hash(json(envelope));
        tx.executeWithoutResult(status->{
            int inserted=jdbc.update("INSERT IGNORE INTO inventory_result_receipt(event_id,body_hash) VALUES(?,?)",envelope.eventId().toString(),bodyHash);
            String stored=jdbc.queryForObject("SELECT body_hash FROM inventory_result_receipt WHERE event_id=? FOR UPDATE",String.class,envelope.eventId().toString());
            if(!bodyHash.equals(stored)) throw new IllegalArgumentException("Conflicting inventory event identity");
            if(inserted==0) return;
            Row row=row(result.orderId(),true);
            if(row==null) throw new IllegalArgumentException("Inventory result references unknown order");
            if(!row.reservation.equals(result.reservationId()) || result.generation()!=1 || result.version()<=row.version) return;
            UUID expiry=UUID.nameUUIDFromBytes(("expiry:"+row.reservation).getBytes(StandardCharsets.UTF_8));
            if(!Set.of(row.reserve,row.release,expiry).contains(result.commandId())) throw new IllegalArgumentException("Unknown inventory command result");
            jdbc.update("UPDATE customer_order SET inventory_version=? WHERE id=?",result.version(),row.id.toString());
            // Apply the authoritative reservation state, including a rejection carrying RELEASED.
            if(Set.of("RELEASED","EXPIRED","REJECTED").contains(result.state())) {
                if(row.state.equals("ABORTING")) state(row,row.target,row.reason);
                else if(row.state.equals("PREPARING")) state(row,"REJECTED",result.reason()==null?result.state():result.reason());
                else if(row.state.equals("PENDING_PAYMENT")) state(row,result.state().equals("EXPIRED")?"EXPIRED":"RECONCILIATION_REQUIRED",result.state());
                else if(row.state.equals("PAYMENT_PROCESSING")) state(row,"RECONCILIATION_REQUIRED","PAYMENT_STATUS_UNKNOWN");
                return;
            }
            if(result.state().equals("RESERVED")) {
                if(result.reservedAt()==null || result.expiresAt()==null || !result.expiresAt().isAfter(result.reservedAt()))
                    throw new IllegalArgumentException("Invalid reservation lifetime");
                Instant now=now();
                if(row.state.equals("PREPARING") && result.eventType().equals("InventoryReserved") && row.deadline.isAfter(now) && result.expiresAt().isAfter(now)) {
                    jdbc.update("UPDATE customer_order SET status='PENDING_PAYMENT',reserved_at=?,expires_at=? WHERE id=?",
                            Timestamp.from(result.reservedAt()),Timestamp.from(result.expiresAt()),row.id.toString());
                } else if(row.state.equals("PREPARING")) abort(row,"REJECTED","RESERVATION_TIMEOUT");
                else if(row.state.equals("ABORTING")) release(row);
                else if(!Set.of("PENDING_PAYMENT","PAYMENT_PROCESSING").contains(row.state)) state(row,"RECONCILIATION_REQUIRED","UNEXPECTED_ACTIVE_RESERVATION");
                return;
            }
            state(row,"RECONCILIATION_REQUIRED","UNEXPECTED_INVENTORY_STATE");
        });
    }
    private void abort(Row row,String target,String reason) {
        jdbc.update("UPDATE customer_order SET status='ABORTING',cancellation_target=?,reason=? WHERE id=?",target,reason,row.id.toString());
        release(row);
    }
    private void release(Row row) {
        if(row.releaseRequested) return;
        append(new Command(row.release,"RELEASE",row.id,row.reservation,1,List.of(),null,null),now());
        jdbc.update("UPDATE customer_order SET release_requested=TRUE WHERE id=?",row.id.toString());
    }
    private void append(Command command,Instant now) {
        String order=command.orderId().toString();
        outbox.append(new OutboxRecord(COMMANDS_TOPIC,order,new EventEnvelope(command.commandId(),"InventoryCommand",1,"order-service",order,
                command.type().equals("RESERVE")?1:2,now,order,null,null,mapper.valueToTree(command))));
    }
    private void state(Row row,String state,String reason) {
        jdbc.update("UPDATE customer_order SET status=?,reason=? WHERE id=?",state,reason,row.id.toString());
    }
    private Row owned(UUID user,UUID id,boolean lock) {
        Row row=row(id,lock);
        if(row==null || !row.user.equals(user)) throw error(CommonErrorCode.NOT_FOUND);
        return row;
    }
    private record Row(UUID id,UUID user,String state,String reason,String target,UUID reservation,UUID reserve,UUID release,
            boolean releaseRequested,long version,Instant deadline,Instant expires,Instant created,List<Line> items,long amount,Shipping shipping) { }
    private Row row(UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM customer_order WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Row(id,UUID.fromString(r.getString("user_id")),
                r.getString("status"),r.getString("reason"),r.getString("cancellation_target"),UUID.fromString(r.getString("reservation_id")),
                UUID.fromString(r.getString("reserve_command_id")),UUID.fromString(r.getString("release_command_id")),r.getBoolean("release_requested"),
                r.getLong("inventory_version"),r.getTimestamp("prepare_deadline").toInstant(),r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant(),
                r.getTimestamp("created_at").toInstant(),lines(r.getString("items")),r.getLong("total_amount"),read(r.getString("shipping"),Shipping.class)),id.toString())
                .stream().findFirst().orElse(null);
    }
    private View view(Row row) { return new View(row.id,row.state,row.reason,row.items,row.amount,"KRW",row.shipping,row.created,row.expires); }
    private Instant now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",Timestamp.class).toInstant(); }
    private List<Line> lines(String json) {
        try { return mapper.readValue(json,new TypeReference<List<Line>>(){}); }
        catch(Exception ex) { throw new IllegalStateException("Invalid stored order items"); }
    }
    private <T>T read(String json,Class<T> type) {
        try { return mapper.readValue(json,type); }
        catch(Exception ex) { throw new IllegalStateException("Invalid stored order data"); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception ex) { throw new IllegalArgumentException("Invalid order data"); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException("Digest unavailable"); }
    }
    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
