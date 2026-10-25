package com.pueblo.catalog.inventory;

import java.time.Instant;
import java.util.UUID;

public record InventoryResult(UUID commandId, UUID orderId, UUID reservationId, int generation,
        String eventType, String state, String reason, Instant reservedAt, Instant expiresAt, long version) { }
