package com.pueblo.order.payment;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class PaymentPreparationModels {
    private PaymentPreparationModels() { }
    public record Request(@NotNull UUID paymentId,@NotNull @Min(0) Long amount,@NotBlank @Size(min=3,max=3) String currency) { }
    /** This is an order authorization snapshot, never proof of a successful PG charge. */
    public record Snapshot(UUID orderId,UUID paymentId,long amount,String currency,UUID reservationId,
            Instant createdAt,Instant expiresAt,String orderStatus,String nextAction) { }
}
