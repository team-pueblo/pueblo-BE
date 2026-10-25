package com.pueblo.order.checkout;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("orders")
public final class OrderHealthIndicator implements HealthIndicator {
    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;
    private final boolean enabled;
    public OrderHealthIndicator(ObjectProvider<KafkaListenerEndpointRegistry> registry,
            @Value("${pueblo.orders.enabled:false}") boolean enabled) {
        this.registry = registry; this.enabled = enabled;
    }
    @Override public Health health() {
        if (!enabled) return Health.up().build();
        var value = registry.getIfAvailable();
        var listener = value == null ? null : value.getListenerContainer("order-inventory-results");
        return listener != null && listener.isRunning() ? Health.up().build() : Health.down().build();
    }
}
