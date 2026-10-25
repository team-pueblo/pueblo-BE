package com.pueblo.payment.checkout;
import jakarta.validation.constraints.*;
import java.util.UUID;
public final class PaymentModels {
    private PaymentModels() { }
    public record Confirm(@NotNull UUID orderId,@NotBlank @Size(max=200) @Pattern(regexp="[!-~]+") String paymentKey,@NotNull @Min(1) Long amount) {
        @Override public String toString() { return "Confirm[redacted]"; }
    }
    public record View(UUID paymentId,UUID orderId,long amount,String currency,String status) { }
}
