package com.pueblo.common;

import com.pueblo.common.response.ApiMessageResolver;
import com.pueblo.common.security.SensitiveDataMasker;
import com.pueblo.common.security.SensitiveMessageConverter;
import com.pueblo.common.security.SensitiveThrowableConverter;
import com.pueblo.common.security.redis.SecurityKeyNamespace;
import com.pueblo.common.security.redis.SecurityKeyPurpose;
import com.pueblo.common.security.redis.SecurityTtlPolicy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class SecurityPolicyTest {
    @Test void languageNegotiationAndMissingTranslationAreSafe() {
        var resolver=new ApiMessageResolver(); var request=new MockHttpServletRequest();
        request.addHeader("Accept-Language","fr;q=1,en;q=0.8,ko;q=0.2");
        assertThat(resolver.locale(request)).isEqualTo(Locale.ENGLISH);
        request.removeHeader("Accept-Language"); request.addHeader("Accept-Language","garbage;bad");
        assertThat(resolver.locale(request)).isEqualTo(Locale.KOREAN);
        assertThat(resolver.resolve("missing-sensitive-key",Locale.ENGLISH)).isEqualTo("The request could not be processed.");
        assertThat(resolver.detail("input['private@email.com']","custom-private-value",Locale.ENGLISH).field()).isEqualTo("request");
    }
    @Test void redisKeysRemainCompatibleAndRejectWrongServiceOrExpiry() {
        UUID session=UUID.randomUUID();
        var keys=new SecurityKeyNamespace("pueblo:test:member:security:v1","member");
        assertThat(keys.key(SecurityKeyPurpose.REFRESH_SESSION,session.toString())).isEqualTo("pueblo:test:member:security:v1:refresh:{"+session+"}");
        assertThatThrownBy(()->new SecurityKeyNamespace("pueblo:test:order:security:v1","member")).isInstanceOf(IllegalArgumentException.class);
        Instant now=Instant.parse("2026-10-25T00:00:00Z"); Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        assertThat(SecurityTtlPolicy.refreshExpiryMillis(now.plus(Duration.ofDays(14)),clock)).isEqualTo(now.plus(Duration.ofDays(14)).toEpochMilli());
        assertThatThrownBy(()->SecurityTtlPolicy.refreshExpiryMillis(now,clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SecurityTtlPolicy.refreshExpiryMillis(now.plus(Duration.ofDays(15)),clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SecurityTtlPolicy.active(-1L)).isFalse(); assertThat(SecurityTtlPolicy.active(-2L)).isFalse(); assertThat(SecurityTtlPolicy.active(null)).isFalse();
    }
    @Test void messageMaskerCoversCredentialFormsAndLogInjection() {
        String input="password=top-secret, email=user@example.com, phone=010-1234-5678, accessToken=token-secret\nAuthorization: Bearer credential-secret\nCookie: refresh=cookie-secret; csrf=csrf-secret\nurl=https://user:url-secret@example.com";
        String masked=SensitiveDataMasker.mask(input);
        assertThat(masked).doesNotContain("top-secret","user@example.com","010-1234-5678","token-secret","credential-secret","cookie-secret","csrf-secret","url-secret","\n","\r");
        assertThat(SensitiveDataMasker.mask("{\"password\":\"with escaped \\\" quote\",\"nickname\":\"public\"}"))
                .doesNotContain("with escaped").contains("public");
        assertThat(SensitiveDataMasker.mask("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmaXh0dXJlIn0.abcdefghijklmno"))
                .isEqualTo("[REDACTED]");
        assertThat(SensitiveDataMasker.mask("password=secret with spaces")).doesNotContain("secret","with spaces");
        assertThat(SensitiveDataMasker.mask("shipping={\"recipient\":\"Private Name\",\n\"city\":\"Private City\"}"))
                .doesNotContain("Private Name","Private City");
        assertThat(SensitiveDataMasker.mask("password=\""+"x".repeat(16000)+"\"" )).doesNotContain("xxxxx");
    }
    @Test void actualLogbackLayoutMasksMessagesAndThrowableChains() {
        var context=new ch.qos.logback.classic.LoggerContext();
        try {
            var layout=new ch.qos.logback.classic.PatternLayout(); layout.setContext(context);
            layout.getInstanceConverterMap().put("safeMsg",SensitiveMessageConverter::new);
            layout.getInstanceConverterMap().put("safeEx",SensitiveThrowableConverter::new);
            layout.setPattern("%safeMsg%n%safeEx"); layout.start();
            var cause=new IllegalArgumentException("unlabelled-private-cause");
            var exception=new IllegalStateException("unlabelled-private-message",cause);
            exception.addSuppressed(new RuntimeException("unlabelled-private-suppressed"));
            var event=new ch.qos.logback.classic.spi.LoggingEvent("test",context.getLogger("test"),ch.qos.logback.classic.Level.ERROR,
                    "password={} email={}",exception,new Object[]{"credential-secret","private@example.com"});
            String rendered=layout.doLayout(event);
            assertThat(rendered).contains("IllegalStateException","Caused by:","Suppressed:")
                    .doesNotContain("unlabelled-private","credential-secret","private@example.com");
            layout.stop();
        } finally { context.stop(); }
    }
}
