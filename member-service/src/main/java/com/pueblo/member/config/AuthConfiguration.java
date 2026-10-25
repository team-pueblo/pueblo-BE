package com.pueblo.member.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.pueblo.member.auth.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
    @Bean SecretKey signingKey(@Value("${pueblo.auth.jwt-token}") String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        if (token.isBlank() || bytes.length < 32) {
            throw new IllegalArgumentException("JWT_TOKEN must contain at least 32 UTF-8 bytes of random secret material");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
    @Bean TokenService tokenService(SecretKey key, AuthRepository repository, AuthProperties properties, Clock clock, RedisRefreshTokens refreshStore) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, new JwtIssuerValidator(properties.issuer()), jwt ->
                validClaims(jwt, properties, clock)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null))));
        return new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), decoder, repository, properties, clock, refreshStore);
    }
    private static boolean validClaims(Jwt jwt, AuthProperties properties, Clock clock) {
        try {
            return jwt.getAudience().equals(java.util.List.of(properties.audience()))
                    && jwt.getExpiresAt()!=null && jwt.getIssuedAt()!=null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(5))
                    && java.time.Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt()).getSeconds()
                       <= ("access".equals(jwt.getClaimAsString("token_use")) ? 300 : 1209600)
                    && java.util.Set.of("access","refresh").contains(jwt.getClaimAsString("token_use"))
                    && java.util.Set.of("MEMBER","ADMIN").contains(jwt.getClaimAsString("role"))
                    && "JWT".equals(jwt.getHeaders().get("typ"))
                    && java.util.UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())
                    && java.util.UUID.fromString(jwt.getId()).toString().equals(jwt.getId())
                    && java.util.UUID.fromString(jwt.getClaimAsString("sid")).toString().equals(jwt.getClaimAsString("sid"));
        } catch(RuntimeException ex) { return false; }
    }
    @Bean JwtDecoder jwtDecoder(TokenService tokens) { return tokens::decodeAccess; }
}
