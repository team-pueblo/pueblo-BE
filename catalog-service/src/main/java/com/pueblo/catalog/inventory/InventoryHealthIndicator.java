package com.pueblo.catalog.inventory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("inventory")
public final class InventoryHealthIndicator implements HealthIndicator {
    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;
    private final boolean enabled;
    public InventoryHealthIndicator(ObjectProvider<KafkaListenerEndpointRegistry> registry,
            @Value("${pueblo.inventory.enabled:false}") boolean enabled,
            @Value("${pueblo.inventory.listener-enabled:false}") boolean listenerEnabled) {
        this.registry = registry; this.enabled = enabled && listenerEnabled;
    }
    @Override public Health health() {
        if (!enabled) return Health.up().build();
        var value = registry.getIfAvailable();
        var listener = value == null ? null : value.getListenerContainer("inventory-commands");
        return listener != null && listener.isRunning() ? Health.up().build() : Health.down().build();
    }
}
