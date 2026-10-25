package com.pueblo.member.auth;

import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import com.pueblo.member.exception.MemberErrorCode;
import com.pueblo.member.mail.AuthMailJobs;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    public record Consent(String policyType, String version, boolean agreed) { }
    public record UserView(String id, String email, String nickname) { }
    public record Tokens(String accessToken, String refreshToken, Instant sessionExpiresAt, UserView user) {
        @Override public String toString() { return "Tokens[redacted]"; }
    }
    private record Rotation(Tokens tokens, BusinessException error) { }
    private record OneTime(String id, String userId, String purpose, Instant expiresAt, Instant usedAt) { }
    private final RedisRefreshTokens refreshStore;
    private final JdbcTemplate jdbc;
    private final AuthRepository repository;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final AuthMailJobs mail;
    private final AuthRateLimiter limits;
    private final TransactionTemplate transaction;
    private final String dummyHash;
    public AuthService(JdbcTemplate jdbc, AuthRepository repository, PasswordEncoder passwords, TokenService tokens,
            AuthProperties properties, Clock clock, AuthMailJobs mail, AuthRateLimiter limits, PlatformTransactionManager manager, RedisRefreshTokens refreshStore) {
        this.refreshStore=refreshStore;
        this.jdbc=jdbc; this.repository=repository; this.passwords=passwords; this.tokens=tokens; this.properties=properties;
        this.clock=clock; this.mail=mail; this.limits=limits; this.transaction=new TransactionTemplate(manager);
        dummyHash=passwords.encode("internal-nonexistent-account-password");
    }
    public void signup(String rawEmail, String password, String nickname, List<Consent> consents) {
        String email = email(rawEmail);
        validatePassword(password);
        if (nickname == null || nickname.isBlank() || nickname.length() > 50) throw invalid();
        Map<String,String> required = Map.of("TERMS", properties.termsVersion(), "PRIVACY", properties.privacyVersion());
        if (consents == null || consents.size()!=2 || consents.stream().anyMatch(java.util.Objects::isNull) || consents.stream().map(Consent::policyType).distinct().count()!=2
                || consents.stream().anyMatch(c -> !c.agreed() || c.version()==null || !c.version().equals(required.get(c.policyType())))) {
            throw new BusinessException(MemberErrorCode.CONSENT_REQUIRED);
        }
        limits.check("signup", email, 5, Duration.ofHours(1));
        String hash = passwords.encode(password);
        try {
            transaction.executeWithoutResult(status -> {
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO member_user (id,email,password_hash,nickname,created_at) VALUES (?,?,?,?,?)",
                        id,email,hash,nickname.strip(),now());
                for (Consent c : consents) jdbc.update("INSERT INTO member_consent (user_id,policy_type,version,agreed_at) VALUES (?,?,?,?)",
                        id,c.policyType(),c.version(),now());
                issueOneTime(id,"VERIFY",Duration.ofMinutes(30));
            });
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }
    public Tokens login(String rawEmail, String password) {
        String email=email(rawEmail);
        limits.check("login", email, 10, Duration.ofMinutes(15));
        var found=repository.byEmail(email);
        boolean matched=passwords.matches(password, found.map(AuthRepository.User::passwordHash).orElse(dummyHash));
        if (!matched || found.isEmpty() || !"ACTIVE".equals(found.get().status())) throw BusinessException.unauthorized();
        AuthRepository.User candidate=found.get();
        return transaction.execute(status -> {
            AuthRepository.User user=repository.byId(candidate.id(),true).orElseThrow(BusinessException::unauthorized);
            if (!user.passwordHash().equals(candidate.passwordHash()) || !"ACTIVE".equals(user.status())) throw BusinessException.unauthorized();
            String session=UUID.randomUUID().toString();
            Instant expires=clock.instant().plus(Duration.ofDays(14)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            jdbc.update("INSERT INTO auth_session (id,user_id,created_at,expires_at) VALUES (?,?,?,?)", session,user.id(),now(),Timestamp.from(expires));
            var issued=issue(user, session, expires);
            refreshStore.create(session,issued.refreshToken(),expires);
            return issued;
        });
    }
    public Tokens refresh(String raw) {
        Jwt jwt;
        try { jwt=tokens.decodeRefresh(raw); } catch (JwtException | IllegalArgumentException ex) { throw BusinessException.unauthorized(); }
        String sessionId=jwt.getClaimAsString("sid");
        limits.check("refresh", sessionId, 60, Duration.ofMinutes(1));
        Rotation result=transaction.execute(status -> {
            // Match lock ordering with password reset/login: user before sessions/tokens.
            var initial=repository.session(sessionId,false).orElseThrow(BusinessException::unauthorized);
            var user=repository.byId(initial.userId(),true).orElseThrow(BusinessException::unauthorized);
            var session=repository.session(sessionId,true).orElseThrow(BusinessException::unauthorized);
            if (!session.userId().equals(jwt.getSubject()) || session.revokedAt()!=null
                    || !session.expiresAt().isAfter(clock.instant()) || !"ACTIVE".equals(user.status())) throw BusinessException.unauthorized();
            var issued=issue(user,sessionId,session.expiresAt());
            long outcome=refreshStore.rotate(sessionId,raw,issued.refreshToken());
            if(outcome==2) return new Rotation(null,new BusinessException(MemberErrorCode.REFRESH_ALREADY_ROTATED));
            if(outcome==3) {
                jdbc.update("UPDATE auth_session SET revoked_at=? WHERE id=?",now(),sessionId);
                return new Rotation(null,new BusinessException(MemberErrorCode.REFRESH_REUSE_DETECTED));
            }
            if(outcome!=1) throw BusinessException.unauthorized();
            return new Rotation(issued,null);
        });
        if (result.error()!=null) throw result.error();
        return result.tokens();
    }
    public void logout(String raw) {
        if (raw==null) return;
        Jwt jwt;
        try { jwt=tokens.decodeRefresh(raw); } catch (JwtException | IllegalArgumentException ex) { return; }
        transaction.executeWithoutResult(status -> {
            var session=repository.session(jwt.getClaimAsString("sid"),false);
            if (session.isEmpty() || !session.get().userId().equals(jwt.getSubject())) return;
            repository.byId(session.get().userId(),true).orElseThrow(BusinessException::unauthorized);
            refreshStore.delete(session.get().id());
            jdbc.update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",now(),session.get().id());
        });
    }
    public void requestVerification(String rawEmail) { requestOneTime(rawEmail,"VERIFY"); }
    public void requestPasswordReset(String rawEmail) { requestOneTime(rawEmail,"RESET"); }
    private void requestOneTime(String rawEmail, String purpose) {
        String email=email(rawEmail);
        limits.check(purpose,email,5,Duration.ofHours(1));
        var user=repository.byEmail(email);
        if (user.isEmpty()) return;
        transaction.executeWithoutResult(status -> {
            var locked=repository.byId(user.get().id(),true).orElseThrow(BusinessException::unauthorized);
            if (purpose.equals("VERIFY") && locked.status().equals("ACTIVE")) {
                throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_VERIFIED);
            }
            if ((purpose.equals("VERIFY") && !locked.status().equals("PENDING"))
                    || (purpose.equals("RESET") && !locked.status().equals("ACTIVE"))) return;
            issueOneTime(locked.id(),purpose,Duration.ofMinutes(purpose.equals("VERIFY")?30:15));
        });
    }
    public void verifyEmail(String raw) { consumeOneTime(raw,"VERIFY",null); }
    public void resetPassword(String raw, String newPassword) {
        validatePassword(newPassword);
        consumeOneTime(raw,"RESET",passwords.encode(newPassword));
    }
    private void consumeOneTime(String raw, String purpose, String newHash) {
        if (raw==null || raw.length()!=43) throw expired();
        transaction.executeWithoutResult(status -> {
            var initial=oneTime(raw,false).orElseThrow(AuthService::expired);
            var user=repository.byId(initial.userId(),true).orElseThrow(AuthService::expired);
            var token=oneTime(raw,true).orElseThrow(AuthService::expired);
            if (!purpose.equals(token.purpose()) || token.usedAt()!=null || !token.expiresAt().isAfter(clock.instant())) throw expired();
            if (purpose.equals("VERIFY")) {
                if (!user.status().equals("PENDING")) throw expired();
                jdbc.update("UPDATE member_user SET status='ACTIVE' WHERE id=?",user.id());
            } else {
                if (!user.status().equals("ACTIVE")) throw expired();
                jdbc.update("UPDATE member_user SET password_hash=? WHERE id=?",newHash,user.id());
                for(String sessionId:jdbc.queryForList("SELECT id FROM auth_session WHERE user_id=? AND revoked_at IS NULL",String.class,user.id())) refreshStore.delete(sessionId);
                jdbc.update("UPDATE auth_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",now(),user.id());
            }
            jdbc.update("UPDATE auth_one_time_token SET used_at=? WHERE user_id=? AND purpose=? AND used_at IS NULL",now(),user.id(),purpose);
        });
    }
    private java.util.Optional<OneTime> oneTime(String raw, boolean lock) {
        return jdbc.query("SELECT * FROM auth_one_time_token WHERE token_hash=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new OneTime(rs.getString("id"),rs.getString("user_id"),rs.getString("purpose"),
                        AuthRepository.time(rs,"expires_at"),AuthRepository.time(rs,"used_at")),TokenSecrets.hash(raw)).stream().findFirst();
    }
    private void issueOneTime(String userId, String purpose, Duration lifetime) {
        jdbc.update("UPDATE auth_one_time_token SET used_at=? WHERE user_id=? AND purpose=? AND used_at IS NULL",now(),userId,purpose);
        String id=UUID.randomUUID().toString(); String raw=TokenSecrets.random();
        jdbc.update("INSERT INTO auth_one_time_token (id,user_id,purpose,token_hash,expires_at) VALUES (?,?,?,?,?)",
                id,userId,purpose,TokenSecrets.hash(raw),Timestamp.from(clock.instant().plus(lifetime)));
        mail.enqueue(id,raw);
    }
    private Tokens issue(AuthRepository.User user, String sessionId, Instant expires) {
        String jti=UUID.randomUUID().toString();
        String refresh=tokens.issue(user,sessionId,jti,"refresh",expires);
        return new Tokens(tokens.issue(user,sessionId,UUID.randomUUID().toString(),"access",expires),refresh,expires,view(user));
    }
    public UserView me(String id) { return view(repository.byId(id,false).orElseThrow(BusinessException::unauthorized)); }
    private static UserView view(AuthRepository.User user) { return new UserView(user.id(),user.email(),user.nickname()); }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    public static String email(String value) {
        if (value==null) throw invalid();
        String email=value.strip().toLowerCase(Locale.ROOT);
        if (email.length()>254 || !email.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+")) throw invalid();
        return email;
    }
    private static void validatePassword(String value) {
        if (value==null || value.isBlank() || value.length()<10 || value.length()>128) throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_INPUT); }
    private static BusinessException expired() { return new BusinessException(MemberErrorCode.TOKEN_EXPIRED_OR_USED); }
}
