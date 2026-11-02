package com.pueblo.payment.checkout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
@Component("paymentRefunds")
public final class RefundHealthIndicator implements HealthIndicator {
    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;
    private final boolean enabled;
    public RefundHealthIndicator(ObjectProvider<KafkaListenerEndpointRegistry> registry,@Value("${pueblo.payments.refunds-enabled:false}") boolean enabled) {this.registry=registry;this.enabled=enabled;}
    @Override public Health health() {
        if(!enabled) return Health.up().build();
        var r=registry.getIfAvailable();var listener=r==null?null:r.getListenerContainer("payment-refunds");
        return listener!=null&&listener.isRunning()?Health.up().build():Health.down().build();
    }
}
