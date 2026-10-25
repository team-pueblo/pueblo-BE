package com.pueblo.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pueblo.messaging")
public record MessagingProperties(
        @DefaultValue("10") int maxPerPoll,
        @DefaultValue("30000") long leaseMs,
        @DefaultValue("6000") long sendTimeoutMs,
        @DefaultValue("20") int maxAttempts,
        @DefaultValue("1000") long retryBaseMs,
        @DefaultValue("30000") long retryMaxMs) {
    public MessagingProperties {
        if (maxPerPoll < 1 || maxAttempts < 1 || sendTimeoutMs < 1
                || leaseMs <= 2 * sendTimeoutMs || retryBaseMs < 1 || retryMaxMs < retryBaseMs) {
            throw new IllegalArgumentException("Invalid outbox polling, lease, or retry configuration");
        }
    }

    long retryDelayMs(int attempt) {
        long delay = retryBaseMs;
        for (int i = 1; i < attempt && delay < retryMaxMs; i++) {
            delay = delay > retryMaxMs / 2 ? retryMaxMs : delay * 2;
        }
        return Math.min(delay, retryMaxMs);
    }
}
