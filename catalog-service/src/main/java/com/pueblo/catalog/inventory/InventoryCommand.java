package com.pueblo.catalog.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public record InventoryCommand(UUID commandId, Type type, UUID orderId, UUID reservationId,
        int generation, List<Item> items, Instant commandExpiresAt, UUID refundId) {
    public enum Type { RESERVE, REACQUIRE, COMMIT, RELEASE, RESTORE, EXPIRE }
    public record Item(UUID skuId, int quantity,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long expectedUnitPrice) {
        public Item(UUID skuId, int quantity) { this(skuId, quantity, null); }
    }

    public InventoryCommand {
        if (commandId == null || type == null || orderId == null || reservationId == null || generation < 1) {
            throw new IllegalArgumentException("Invalid inventory command identity");
        }
        boolean reserve = type == Type.RESERVE || type == Type.REACQUIRE;
        if (items == null || items.size() > 100 || (reserve && items.isEmpty())
                || (!reserve && !items.isEmpty()) || (reserve != (commandExpiresAt != null))
                || ((type == Type.RESTORE) != (refundId != null))) {
            throw new IllegalArgumentException("Invalid inventory command fields");
        }
        Map<String, Integer> quantities = new TreeMap<>();
        Map<String, Long> prices = new java.util.HashMap<>();
        for (Item item : items) {
            if (item == null || item.skuId() == null || item.quantity() < 1 || item.quantity() > 9) {
                throw new IllegalArgumentException("Invalid inventory quantity");
            }
            if (item.expectedUnitPrice() != null && (item.expectedUnitPrice() < 0 || item.expectedUnitPrice() > 1_000_000_000_000L))
                throw new IllegalArgumentException("Invalid expected price");
            String id = item.skuId().toString();
            if (prices.containsKey(id) && !java.util.Objects.equals(prices.get(id), item.expectedUnitPrice()))
                throw new IllegalArgumentException("Conflicting expected prices");
            prices.put(id, item.expectedUnitPrice());
            int quantity = quantities.merge(item.skuId().toString(), item.quantity(), Integer::sum);
            if (quantity > 9) throw new IllegalArgumentException("Combined SKU quantity exceeds nine");
        }
        items = quantities.entrySet().stream().map(e -> new Item(UUID.fromString(e.getKey()), e.getValue(), prices.get(e.getKey()))).toList();
        if (commandExpiresAt != null) commandExpiresAt = commandExpiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
