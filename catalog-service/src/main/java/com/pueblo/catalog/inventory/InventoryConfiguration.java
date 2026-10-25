package com.pueblo.catalog.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.messaging.JdbcOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "pueblo.inventory.enabled", havingValue = "true")
public class InventoryConfiguration {
    @Bean
    InventoryService inventoryService(JdbcTemplate jdbc, ObjectMapper mapper, JdbcOutbox outbox, PlatformTransactionManager manager) {
        return new InventoryService(jdbc, mapper, outbox, manager);
    }
    @Bean
    @ConditionalOnProperty(name = "pueblo.inventory.expiry-enabled", havingValue = "true", matchIfMissing = true)
    ExpiryJob inventoryExpiryJob(InventoryService service) { return new ExpiryJob(service); }

    static final class ExpiryJob {
        private final InventoryService service;
        ExpiryJob(InventoryService service) { this.service = service; }
        @Scheduled(fixedDelayString = "${pueblo.inventory.expiry-interval-ms:10000}")
        public void expire() { service.expireBatch(); }
    }
}
