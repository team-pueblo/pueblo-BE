package com.pueblo.common.security.redis;

import java.time.*;

public final class SecurityTtlPolicy {
    private SecurityTtlPolicy() { }
    public static long refreshExpiryMillis(Instant expiry,Clock clock) {
        Instant now=clock.instant();
        if(expiry==null || !expiry.isAfter(now) || expiry.isAfter(now.plus(Duration.ofDays(14))))
            throw new IllegalArgumentException("Refresh expiry must be within fourteen days");
        return expiry.toEpochMilli();
    }
    public static boolean active(Long remainingMillis) { return remainingMillis!=null && remainingMillis>0; }
}
