package com.pueblo.order.checkout;

import com.pueblo.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.pueblo.order.checkout.OrderModels.*;

@RestController
@ConditionalOnProperty(name="pueblo.orders.enabled",havingValue="true")
@RequestMapping("/api/v1")
@SecurityRequirement(name="bearerAuth")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service=service; }
    @PostMapping("/checkout/quotes") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Quote> quote(Principal principal,@Valid @RequestBody QuoteRequest body,HttpServletRequest request) {
        return ApiResponse.of(service.quote(UUID.fromString(principal.getName()),body),request);
    }
    @PostMapping("/orders") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Accepted> create(Principal principal,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateRequest body,HttpServletRequest request) {
        return ApiResponse.of(service.create(UUID.fromString(principal.getName()),key,body),request);
    }
    @GetMapping("/me/orders/{id}")
    public ApiResponse<View> get(Principal principal,@PathVariable UUID id,HttpServletRequest request) {
        return ApiResponse.of(service.get(UUID.fromString(principal.getName()),id),request);
    }
    @GetMapping("/me/orders")
    public ApiResponse<Page> list(Principal principal,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        return ApiResponse.of(service.list(UUID.fromString(principal.getName()),page,size),request);
    }
    @PostMapping("/me/orders/{id}/cancellations") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<View> cancel(Principal principal,@PathVariable UUID id,HttpServletRequest request) {
        return ApiResponse.of(service.cancel(UUID.fromString(principal.getName()),id),request);
    }
}
