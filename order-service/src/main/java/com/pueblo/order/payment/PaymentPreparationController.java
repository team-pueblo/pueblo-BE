package com.pueblo.order.payment;

import com.pueblo.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import static com.pueblo.order.payment.PaymentPreparationModels.*;

@Hidden // Machine-only contract is documented in docs/order-payment-preparation.md, not browser Swagger.
@RestController
@ConditionalOnProperty(name={"pueblo.orders.enabled","pueblo.orders.payment-preparation.enabled"},havingValue="true")
@RequestMapping("/internal/v1/orders/{id}/payment-attempts")
public class PaymentPreparationController {
    private final PaymentPreparationService service;
    public PaymentPreparationController(PaymentPreparationService service) { this.service=service; }
    @PostMapping
    public ApiResponse<Snapshot> begin(@PathVariable UUID id,Principal user,@Valid @RequestBody Request body,HttpServletRequest request) {
        return ApiResponse.of(service.begin(UUID.fromString(user.getName()),id,body),request);
    }
    @GetMapping
    public ApiResponse<Snapshot> get(@PathVariable UUID id,HttpServletRequest request) { return ApiResponse.of(service.get(id),request); }
}
