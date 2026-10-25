package com.pueblo.messaging;

import org.springframework.scheduling.annotation.Scheduled;

public final class OutboxPollingJob {
    private final OutboxDispatcher dispatcher;

    public OutboxPollingJob(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${pueblo.messaging.poll-interval-ms:1000}")
    public void poll() {
        dispatcher.poll();
    }
}
