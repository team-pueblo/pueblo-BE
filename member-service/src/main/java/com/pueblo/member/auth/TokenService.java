package com.pueblo.member.auth;

import com.pueblo.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

public class TokenService {
    private final RedisRefreshTokens refreshStore;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AuthRepository repository;
    private final AuthProperties properties;
    private final Clock clock;
    public TokenService(JwtEncoder encoder, JwtDecoder decoder, AuthRepository repository, AuthProperties properties, Clock clock, RedisRefreshTokens refreshStore) {
        this.refreshStore=refreshStore;
        this.encoder = encoder; this.decoder = decoder; this.repository = repository; this.properties = properties; this.clock = clock;
    }
    public String issue(AuthRepository.User user, String sessionId, String jti, String type, Instant sessionExpiry) {
        Instant now = clock.instant();
        Instant expires = type.equals("access") ? now.plus(Duration.ofMinutes(5)) : sessionExpiry;
        if (expires.isAfter(sessionExpiry)) expires = sessionExpiry;
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(user.id()).issuedAt(now).expiresAt(expires).id(jti)
                .claim("sid", sessionId).claim("token_use", type).claim("role", user.role()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }
    public Jwt decodeRefresh(String raw) {
        Jwt jwt = decoder.decode(raw);
        if (!"refresh".equals(jwt.getClaimAsString("token_use"))) throw new BadJwtException("Wrong token use");
        return jwt;
    }
    public Jwt decodeAccess(String raw) {
        Jwt jwt = decoder.decode(raw);
        if (!"access".equals(jwt.getClaimAsString("token_use"))) throw new BadJwtException("Wrong token use");
        AuthRepository.Session session = repository.session(jwt.getClaimAsString("sid"), false)
                .orElseThrow(() -> new BadJwtException("Unknown session"));
        if (!session.userId().equals(jwt.getSubject()) || session.revokedAt() != null
                || !session.expiresAt().isAfter(clock.instant()) || !"ACTIVE".equals(session.userStatus())
                || !session.role().equals(jwt.getClaimAsString("role"))) {
            throw new BadJwtException("Inactive session");
        }
        try {
            if(!refreshStore.isActive(session.id())) throw new BadJwtException("Inactive credential store");
        } catch(BusinessException ex) {
            throw ex;
        }
        return jwt;
    }
}
