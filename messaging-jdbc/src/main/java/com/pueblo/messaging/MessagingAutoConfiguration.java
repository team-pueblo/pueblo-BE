package com.pueblo.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, KafkaAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnBean({JdbcTemplate.class, KafkaTemplate.class, PlatformTransactionManager.class, ObjectMapper.class})
@ConditionalOnProperty(prefix = "pueblo.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
public class MessagingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    JdbcOutbox jdbcOutbox(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${spring.application.name}") String producer,
            PlatformTransactionManager manager, MessagingProperties properties) {
        return new JdbcOutbox(jdbc, mapper, producer, manager, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcEventConsumer jdbcEventConsumer(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcEventConsumer(jdbc, manager);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    EventPublisher kafkaEventPublisher(KafkaTemplate<Object, Object> kafka, ObjectMapper mapper,
            MessagingProperties properties) {
        return new KafkaEventPublisher(kafka, mapper, properties.sendTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxDispatcher outboxDispatcher(JdbcOutbox outbox, EventPublisher publisher, MessagingProperties properties) {
        return new OutboxDispatcher(outbox, publisher, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pueblo.messaging", name = "polling-enabled", havingValue = "true", matchIfMissing = true)
    OutboxPollingJob outboxPollingJob(OutboxDispatcher dispatcher) {
        return new OutboxPollingJob(dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler messagingErrorHandler() {
        // Never skip a failed business event and silently advance its offset.
        // A service-specific bounded retry / quarantine policy can override this bean later.
        return new CommonContainerStoppingErrorHandler();
    }
}
