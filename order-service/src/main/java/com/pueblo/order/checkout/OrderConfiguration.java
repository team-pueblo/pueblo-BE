package com.pueblo.order.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.messaging.*;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="pueblo.orders.enabled",havingValue="true")
public class OrderConfiguration {
    @Bean ServiceClients serviceClients(@Value("${pueblo.orders.member-url:http://localhost:8081}") String member,
            @Value("${pueblo.orders.catalog-url:http://localhost:8082}") String catalog,ObjectMapper mapper) {
        return new ServiceClients(member,catalog,mapper);
    }
    @Bean OrderService orderService(JdbcTemplate jdbc,ObjectMapper mapper,JdbcOutbox outbox,ServiceClients clients,
            PlatformTransactionManager manager,@Value("${pueblo.orders.terms-version:purchase-v1}") String terms) {
        return new OrderService(jdbc,mapper,outbox,clients,manager,terms);
    }
    @Bean KafkaRecordCustomizer inventoryCommandSigner(@Value("${pueblo.orders.inventory-secret:}") String secret) {
        HmacMessageSignature signature=new HmacMessageSignature(secret);
        return (source,record)->{
            if(source.topic().equals(OrderService.COMMANDS_TOPIC))
                record.headers().add(new RecordHeader("pueblo-inventory-signature",signature.sign((String)record.value())));
        };
    }
    @Bean OrderTimeoutJob orderTimeoutJob(OrderService service) { return new OrderTimeoutJob(service); }
    public static class OrderTimeoutJob {
        private final OrderService service;
        OrderTimeoutJob(OrderService service) { this.service=service; }
        @Scheduled(fixedDelayString="${pueblo.orders.timeout-poll-ms:1000}") public void run() { service.expireBatch(); }
    }
}
