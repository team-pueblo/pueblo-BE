package com.pueblo.member.auth;

import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AuthRateLimiter(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void check(String scope, String target, int limit, Duration window) {
        byte[] key = TokenSecrets.hash(scope + ":" + target);
        var now = Timestamp.from(clock.instant());
        var cutoff = Timestamp.from(clock.instant().minus(window));
        long[] result = Objects.requireNonNull(transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO auth_rate_limit (key_hash, window_start, attempts) VALUES (?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                    attempts = IF(window_start <= ?, 1, attempts + 1),
                    window_start = IF(window_start <= ?, ?, window_start)
                    """, key, now, cutoff, cutoff, now);
            return jdbc.queryForObject("SELECT attempts, window_start FROM auth_rate_limit WHERE key_hash = ?",
                    (rs, n) -> new long[]{rs.getLong("attempts"), Math.max(1, Duration.between(clock.instant(),
                            AuthRepository.time(rs,"window_start").plus(window)).toSeconds())}, key);
        }));
        if (result[0] > limit) throw new BusinessException(CommonErrorCode.RATE_LIMITED, result[1]);
    }
}
