package com.pueblo.order.checkout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderModels {
    private OrderModels() { }
    public record Item(@NotNull UUID productId, @NotNull UUID skuId, @Min(1) @Max(9) int quantity) { }
    public record QuoteRequest(@NotEmpty @Size(max=20) List<@NotNull @Valid Item> items) { }
    public record Shipping(@NotBlank @Size(max=80) String recipient,
            @NotBlank @Pattern(regexp="[0-9+() -]{8,30}") String phone,
            @NotBlank @Pattern(regexp="[0-9]{5}") String postalCode,
            @NotBlank @Size(max=200) String address1, @Size(max=200) String address2) {
        @Override public String toString() { return "Shipping[REDACTED]"; }
    }
    public record CreateRequest(@NotNull UUID quoteId, @NotNull @Valid Shipping shipping,
            @NotBlank @Size(max=40) String purchaseTermsVersion, @AssertTrue boolean agreed) { }
    public record Line(UUID productId, UUID skuId, String name, String brand,
            java.util.Map<String,String> options, long unitPrice, int quantity, long amount) { }
    public record Quote(UUID quoteId, List<Line> items, long totalAmount, String currency, Instant expiresAt) { }
    public record Accepted(UUID orderId, String status, long totalAmount, String currency, Instant prepareDeadline) { }
    public record View(UUID orderId, String status, String reason, List<Line> items, long totalAmount,
            String currency, Shipping shipping, Instant createdAt, Instant expiresAt) { }
    public record Summary(UUID orderId, String status, long totalAmount, String currency, Instant createdAt) { }
    public record Page(List<Summary> items, int page, int size, long total) { }
    public record Command(UUID commandId, String type, UUID orderId, UUID reservationId,
            int generation, List<CommandItem> items, Instant commandExpiresAt, UUID refundId) { }
    public record CommandItem(UUID skuId, int quantity, Long expectedUnitPrice) { }
    public record Result(UUID commandId, UUID orderId, UUID reservationId, int generation,
            String eventType, String state, String reason, Instant reservedAt, Instant expiresAt, long version) { }
}
