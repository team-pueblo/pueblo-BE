package com.pueblo.messaging;

@FunctionalInterface
public interface EventPublisher {
    void publish(OutboxRecord record) throws Exception;
}
