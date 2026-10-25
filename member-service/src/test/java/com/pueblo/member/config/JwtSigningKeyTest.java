package com.pueblo.member.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JwtSigningKeyTest {
    private final AuthConfiguration configuration = new AuthConfiguration();

    @Test void rejectsMissingOrShortSecrets() {
        for (String secret : new String[]{"", " ".repeat(32), "short-secret"}) {
            assertThatThrownBy(() -> configuration.signingKey(secret))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JWT_TOKEN");
        }
    }

    @Test void usesRawUtf8SecretForHs256() {
        String secret = "test-only-secret-with-at-least-32-bytes";
        var key = configuration.signingKey(secret);
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).isEqualTo(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void decoderRejectsWrongSecretAndAlgorithm() throws Exception {
        var key = configuration.signingKey("test-only-secret-".repeat(4));
        var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        for (boolean wrongSecret : new boolean[]{true, false}) {
            var algorithm = wrongSecret ? com.nimbusds.jose.JWSAlgorithm.HS256 : com.nimbusds.jose.JWSAlgorithm.HS384;
            var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(algorithm),
                    new com.nimbusds.jwt.JWTClaimsSet.Builder().subject("test")
                            .expirationTime(java.util.Date.from(java.time.Instant.now().plusSeconds(60))).build());
            jwt.sign(new com.nimbusds.jose.crypto.MACSigner(wrongSecret
                    ? configuration.signingKey("other-test-secret-".repeat(4)) : key));
            assertThatThrownBy(() -> decoder.decode(jwt.serialize()))
                    .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }
}
