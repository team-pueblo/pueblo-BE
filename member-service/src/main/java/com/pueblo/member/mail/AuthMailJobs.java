package com.pueblo.member.mail;

import com.pueblo.member.auth.AuthProperties;
import com.pueblo.member.auth.AuthRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AuthMailJobs {
    private record Job(String id, String owner, byte[] encrypted, String recipient, String purpose,
            Instant expiresAt, Instant usedAt, int attempts) { }
    private final JdbcTemplate jdbc;
    private final MailCipher cipher;
    private final MailDelivery delivery;
    private final AuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate independent;
    public AuthMailJobs(JdbcTemplate jdbc, MailCipher cipher, MailDelivery delivery, AuthProperties properties,
            Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.cipher = cipher; this.delivery = delivery; this.properties = properties; this.clock = clock;
        mandatory = new TransactionTemplate(manager); mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        independent = new TransactionTemplate(manager); independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public void enqueue(String tokenId, String raw) {
        mandatory.executeWithoutResult(status -> {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO auth_mail_job (id, token_id, encrypted_token, next_attempt_at) VALUES (?, ?, ?, ?)",
                    id, tokenId, cipher.encrypt(raw, id), Timestamp.from(clock.instant()));
        });
    }
    public boolean dispatchOne() {
        Job job = independent.execute(status -> {
            var rows = jdbc.query("""
                    SELECT j.*, t.purpose, t.expires_at, t.used_at, u.email FROM auth_mail_job j
                    JOIN auth_one_time_token t ON j.token_id = t.id JOIN member_user u ON t.user_id = u.id
                    WHERE (j.status = 'PENDING' AND j.next_attempt_at <= ?)
                       OR (j.status = 'PROCESSING' AND j.lease_until <= ?)
                    ORDER BY j.next_attempt_at, j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                    """, (rs, n) -> new Job(rs.getString("id"), UUID.randomUUID().toString(), rs.getBytes("encrypted_token"),
                    rs.getString("email"), rs.getString("purpose"), AuthRepository.time(rs, "expires_at"),
                    AuthRepository.time(rs, "used_at"), rs.getInt("attempts") + 1),
                    Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
            if (rows.isEmpty()) return null;
            Job found = rows.getFirst();
            jdbc.update("UPDATE auth_mail_job SET status='PROCESSING', attempts=attempts+1, lease_owner=?, lease_until=? WHERE id=?",
                    found.owner(), Timestamp.from(clock.instant().plusSeconds(60)), found.id());
            return found;
        });
        if (job == null) return false;
        if (job.usedAt() != null || !job.expiresAt().isAfter(clock.instant())) { finish(job, "SKIPPED", true); return true; }
        if (job.attempts() > 5) { finish(job, "FAILED", false); return true; }
        try {
            String raw = cipher.decrypt(job.encrypted(), job.id());
            String route = "VERIFY".equals(job.purpose()) ? "/verify-email#token=" : "/reset-password#token=";
            String link = properties.frontendBaseUrl().replaceAll("/+$", "") + route + raw;
            delivery.send(job.recipient(), AuthMailTemplate.render(job.purpose(), link));
        } catch (Exception ex) {
            LoggerFactory.getLogger(getClass()).warn("Auth mail send failed jobId={} category={}", job.id(), ex.getClass().getSimpleName());
            finish(job, job.attempts() >= 5 ? "FAILED" : "PENDING", false);
            return true;
        }
        finish(job, "SENT", true);
        return true;
    }
    private void finish(Job job, String state, boolean clear) {
        independent.executeWithoutResult(status -> jdbc.update("""
                UPDATE auth_mail_job SET status=?, encrypted_token=IF(?, NULL, encrypted_token),
                lease_owner=NULL, lease_until=NULL, next_attempt_at=?
                WHERE id=? AND status='PROCESSING' AND lease_owner=?
                """, state, clear, Timestamp.from(clock.instant().plusSeconds(Math.min(30L << Math.min(job.attempts()-1, 4), 300))),
                job.id(), job.owner()));
    }
}
