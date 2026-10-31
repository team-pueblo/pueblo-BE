package com.pueblo.payment.checkout;
import com.pueblo.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pueblo.payment.checkout.PaymentModels.*;
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
@ConditionalOnProperty(name="pueblo.payments.enabled",havingValue="true")
public class PaymentController {
    private final PaymentService service;
    public PaymentController(PaymentService service) { this.service=service; }
    @PostMapping("/api/v1/payments/confirm")
    @io.swagger.v3.oas.annotations.Operation(summary="토스 테스트 결제 승인",description="프론트 인증 결과의 paymentKey와 주문 ID·금액을 전달합니다. 202는 결과 확인 중이며 200도 status를 확인해야 합니다. 주문·재고 확정은 후속 연동입니다.")
    public ResponseEntity<ApiResponse<View>> confirm(Principal user,@Valid @RequestBody Confirm body,HttpServletRequest request) {
        View result=service.confirm(UUID.fromString(user.getName()),request.getHeader("Authorization"),body);
        int code=java.util.Set.of("PREPARING","PROCESSING","UNKNOWN").contains(result.status())?202:200;
        return ResponseEntity.status(code).body(ApiResponse.of(result,request));
    }
    @GetMapping("/api/v1/me/payments/orders/{orderId}")
    @io.swagger.v3.oas.annotations.Operation(summary="본인 주문의 결제 처리 상태 조회")
    public ApiResponse<View> get(Principal user,@PathVariable UUID orderId,HttpServletRequest request) {
        return ApiResponse.of(service.get(UUID.fromString(user.getName()),orderId),request);
    }
}
