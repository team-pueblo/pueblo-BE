package com.pueblo.member;

import com.fasterxml.jackson.databind.*;
import com.pueblo.member.auth.*;
import com.pueblo.member.mail.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={"pueblo.docs.enabled=true","springdoc.api-docs.enabled=true","springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
@Import(AuthenticationIntegrationTest.TimeConfiguration.class)
class AuthenticationIntegrationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4.7");
    @Container static org.testcontainers.containers.GenericContainer<?> redisContainer=new org.testcontainers.containers.GenericContainer<>("redis:8.10.2-alpine")
            .withExposedPorts(6379).withCommand("redis-server","--requirepass","test-only-password");
    static Path keys;
    static {
        try {
            keys=Files.createTempDirectory("pueblo-auth-test-");
            byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
            Files.writeString(keys.resolve("mail.key"),Base64.getEncoder().encodeToString(secret));
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->mysql.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        r.add("spring.data.redis.host",redisContainer::getHost);
        r.add("spring.data.redis.port",()->redisContainer.getMappedPort(6379));
        r.add("spring.data.redis.password",()->"test-only-password"); r.add("spring.data.redis.ssl.enabled",()->false);
        r.add("pueblo.auth.redis-namespace",()->"pueblo:test:member:security:v1");
        r.add("spring.datasource.username",mysql::getUsername); r.add("spring.datasource.password",mysql::getPassword);
        r.add("spring.kafka.bootstrap-servers",()->"localhost:1"); r.add("pueblo.messaging.enabled",()->false);
        r.add("pueblo.auth.mail-polling-enabled",()->false); r.add("pueblo.auth.mail-mode",()->"mock");
        r.add("pueblo.auth.jwt-token",()->"test-only-jwt-secret-at-least-32-bytes-long");
        r.add("pueblo.auth.mail-key-location",()->keys.resolve("mail.key").toUri().toString());
        r.add("pueblo.auth.allowed-origins",()->"https://shop.example");
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-10-24T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired AuthMailJobs jobs;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MailDelivery delivery;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired RedisRefreshTokens refreshStore;
    @Autowired javax.crypto.SecretKey signingKey;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.springframework.data.redis.core.StringRedisTemplate redis;

    @BeforeEach void clean() {
        for(String table:List.of("auth_mail_job","auth_one_time_token","auth_refresh_token","auth_session","member_consent","member_user","auth_rate_limit")) jdbc.update("DELETE FROM "+table);
        var oldKeys=redis.keys("pueblo:test:member:security:v1:*");
        if(oldKeys!=null && !oldKeys.isEmpty()) redis.delete(oldKeys);
        ((RecordingMailDelivery)delivery).clear(); clock.now=Instant.parse("2026-10-24T00:00:00Z");
    }
    String email="member@example.com";
    String password="correct-password-123";
    List<AuthService.Consent> consents=List.of(new AuthService.Consent("TERMS","v1",true),new AuthService.Consent("PRIVACY","v1",true));
    org.springframework.test.web.servlet.request.RequestPostProcessor realCsrf() throws Exception {
        var bootstrap=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        Cookie cookie=bootstrap.getCookie("__Host-pueblo_csrf");
        String token=mapper.readTree(bootstrap.getContentAsString()).at("/data/token").asText();
        return request->{
            var cookies=new ArrayList<Cookie>();
            if(request.getCookies()!=null) cookies.addAll(Arrays.asList(request.getCookies()));
            cookies.add(cookie); request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN",token); return request;
        };
    }
    ResultActions postJson(String path,Object body) throws Exception {
        return mvc.perform(post("/api/v1"+path).with(realCsrf()).contentType("application/json").content(mapper.writeValueAsBytes(body)));
    }
    String deliveredToken() {
        while(jobs.dispatchOne()) { }
        String body=((RecordingMailDelivery)delivery).messages().getLast().body();
        return body.substring(body.indexOf("#token=")+7);
    }
    void active() { auth.signup(email,password,"닉네임",consents); auth.verifyEmail(deliveredToken()); }
    @Test void signupVerifyLoginMeLogoutThroughHttp() throws Exception {
        postJson("/auth/signup",Map.of("email",email,"password",password,"nickname","닉네임","consents",consents)).andExpect(status().isAccepted());
        postJson("/auth/login",Map.of("email",email,"password",password)).andExpect(status().isUnauthorized());
        String token=deliveredToken();
        postJson("/auth/email-verifications",Map.of("token",token)).andExpect(status().isOk());
        postJson("/auth/email-verifications",Map.of("token",token)).andExpect(status().isGone());
        var login=postJson("/auth/login",Map.of("email",email,"password",password)).andExpect(status().isOk()).andReturn();
        JsonNode data=mapper.readTree(login.getResponse().getContentAsString()).get("data");
        assertThat(data.has("refreshToken")).isFalse();
        assertThat(login.getResponse().getHeader("Cache-Control")).contains("no-store");
        Cookie cookie=login.getResponse().getCookie("__Secure-pueblo_refresh");
        assertThat(cookie.isHttpOnly()).isTrue(); assertThat(cookie.getSecure()).isTrue();
        String access=data.get("accessToken").asText();
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email)).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(post("/api/v1/auth/logout").with(realCsrf()).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void simultaneousRefreshIsConflictAndLaterReplayRevokesSession() throws Exception {
        active(); var initial=auth.login(email,password);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<MvcResult> task=()->{ start.await(); return mvc.perform(post("/api/v1/auth/refresh").with(realCsrf()).cookie(new Cookie("__Secure-pueblo_refresh",initial.refreshToken()))).andReturn(); };
            var a=pool.submit(task); var b=pool.submit(task); start.countDown();
            var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertThat(results.stream().map(r->r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200,409);
            var winner=results.stream().filter(r->r.getResponse().getStatus()==200).findFirst().orElseThrow();
            var loser=results.stream().filter(r->r.getResponse().getStatus()==409).findFirst().orElseThrow();
            assertThat(loser.getResponse().getCookie("__Secure-pueblo_refresh")).isNull();
            String access=mapper.readTree(winner.getResponse().getContentAsString()).at("/data/accessToken").asText();
            mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+access)).andExpect(status().isOk());
            clock.now=clock.now.plusSeconds(4);
            mvc.perform(post("/api/v1/auth/refresh").with(realCsrf()).cookie(new Cookie("__Secure-pueblo_refresh",initial.refreshToken()))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("REFRESH_REUSE_DETECTED"));
            mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        }
    }
    @Test void resetRevokesAllDevicesAndTokenIsSingleUse() throws Exception {
        active(); var one=auth.login(email,password); var two=auth.login(email,password);
        postJson("/auth/password-reset-requests",Map.of("email",email)).andExpect(status().isAccepted());
        String token=deliveredToken();
        postJson("/auth/password-resets",Map.of("token",token,"newPassword","new-password-123")).andExpect(status().isOk());
        for(var session:List.of(one,two)) mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+session.accessToken())).andExpect(status().isUnauthorized());
        postJson("/auth/password-resets",Map.of("token",token,"newPassword","other-password-123")).andExpect(status().isGone());
        postJson("/auth/login",Map.of("email",email,"password",password)).andExpect(status().isUnauthorized());
        postJson("/auth/login",Map.of("email",email,"password","new-password-123")).andExpect(status().isOk());
    }
    @Test void csrfBootstrapAndCorsAreEnforced() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        postJson("/auth/signup",Map.of()).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var bootstrap=mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie cookie=bootstrap.getResponse().getCookie("__Host-pueblo_csrf");
        assertThat(cookie.isHttpOnly()).isTrue(); assertThat(cookie.getSecure()).isTrue();
        String token=mapper.readTree(bootstrap.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/v1/auth/password-reset-requests").cookie(cookie).header("X-XSRF-TOKEN",token).contentType("application/json").content("{\"email\":\"nobody@example.com\"}")).andExpect(status().isAccepted());
        mvc.perform(options("/api/v1/auth/login").header("Origin","https://evil.example").header("Access-Control-Request-Method","POST")).andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/auth/login").header("Origin","https://shop.example").header("Access-Control-Request-Method","POST")).andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials","true"));
    }
    @Test void expiryAndFixedSessionDeadline() throws Exception {
        active(); var initial=auth.login(email,password);
        clock.now=clock.now.plusSeconds(301);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+initial.accessToken())).andExpect(status().isUnauthorized());
        var rotated=auth.refresh(initial.refreshToken()); assertThat(rotated.sessionExpiresAt()).isEqualTo(initial.sessionExpiresAt());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+rotated.refreshToken())).andExpect(status().isUnauthorized());
        clock.now=initial.sessionExpiresAt().plusSeconds(1);
        assertThatThrownBy(()->auth.refresh(rotated.refreshToken())).isInstanceOf(com.pueblo.api.ApiException.class);
    }
    @Test void verifiedAccountCannotRequestAnotherVerificationEmail() throws Exception {
        active();
        int jobsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM auth_mail_job", Integer.class);
        int tokensBefore = jdbc.queryForObject("SELECT COUNT(*) FROM auth_one_time_token", Integer.class);
        postJson("/auth/email-verification-requests", Map.of("email", email))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_VERIFIED"))
                .andExpect(jsonPath("$.error.message").value("이미 이메일 인증이 완료된 계정입니다. 로그인해 주세요."));
        mvc.perform(post("/api/v1/auth/email-verification-requests").with(realCsrf())
                .header("Accept-Language", "en").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("This email has already been verified. Please sign in."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_mail_job", Integer.class)).isEqualTo(jobsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_one_time_token", Integer.class)).isEqualTo(tokensBefore);
        postJson("/auth/email-verification-requests", Map.of("email", "absent@example.com"))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_mail_job", Integer.class)).isEqualTo(jobsBefore);
        postJson("/auth/password-reset-requests", Map.of("email", email)).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_mail_job", Integer.class)).isEqualTo(jobsBefore + 1);
    }
    @Test void resendInvalidatesOldLinkAndExpiredLinkFails() {
        auth.signup(email,password,"nickname",consents); String old=deliveredToken();
        auth.requestVerification(email); String fresh=deliveredToken();
        assertThatThrownBy(()->auth.verifyEmail(old)).isInstanceOf(com.pueblo.api.ApiException.class);
        clock.now=clock.now.plusSeconds(1801);
        assertThatThrownBy(()->auth.verifyEmail(fresh)).isInstanceOf(com.pueblo.api.ApiException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM member_user",String.class)).isEqualTo("PENDING");
    }
    @Test void duplicateAndMalformedConsentHaveNoPartialWrites() throws Exception {
        auth.signup(email,password,"nickname",consents);
        postJson("/auth/signup",Map.of("email",email.toUpperCase(),"password",password,"nickname","nickname","consents",consents)).andExpect(status().isConflict());
        postJson("/auth/signup",Map.of("email","second@example.com","password",password,"nickname","nickname","consents",List.of(Map.of("policyType","TERMS","agreed",true),Map.of("policyType","PRIVACY","version","v1","agreed",true)))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_user",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_mail_job",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM member_user",String.class)).startsWith("{pbkdf2}");
        deliveredToken(); assertThat(jdbc.queryForObject("SELECT encrypted_token FROM auth_mail_job",byte[].class)).isNull();
    }
    @Test void recoveryResponsesHideAccountExistenceAndRateLimit() throws Exception {
        active();
        var exists=postJson("/auth/password-reset-requests",Map.of("email",email)).andExpect(status().isAccepted()).andReturn();
        var absent=postJson("/auth/password-reset-requests",Map.of("email","absent@example.com")).andExpect(status().isAccepted()).andReturn();
        assertThat(mapper.readTree(exists.getResponse().getContentAsString()).get("data")).isEqualTo(mapper.readTree(absent.getResponse().getContentAsString()).get("data"));
        for(int i=0;i<4;i++) auth.requestPasswordReset("absent@example.com");
        postJson("/auth/password-reset-requests",Map.of("email","absent@example.com")).andExpect(status().isTooManyRequests());
    }
    @Test void mailFailureIsRetriedAndEncryptedPayloadClearedAfterSuccess() {
        auth.signup(email,password,"nickname",consents);
        byte[] encrypted=jdbc.queryForObject("SELECT encrypted_token FROM auth_mail_job",byte[].class);
        assertThat(encrypted).isNotEmpty();
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated SMTP failure"))
                .doCallRealMethod().when(delivery).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(MailContent.class));
        assertThat(jobs.dispatchOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM auth_mail_job",String.class)).isEqualTo("PENDING");
        assertThat(jobs.dispatchOne()).isFalse();
        clock.now=clock.now.plusSeconds(31);
        String token=deliveredToken();
        assertThat(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT status FROM auth_mail_job",String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT encrypted_token FROM auth_mail_job",byte[].class)).isNull();
        auth.verifyEmail(token);
    }
    @Test void enrollmentAndMailJobRollBackTogether() {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
            auth.signup(email,password,"nickname",consents); status.setRollbackOnly();
        });
        for(String table:List.of("member_user","member_consent","auth_one_time_token","auth_mail_job"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
    }
    @Test void redisHasNamespaceHashAndFixedExpiryAndMissingStateDeniesAccess() throws Exception {
        active(); var issued=auth.login(email,password);
        var keys=redis.keys("pueblo:test:member:security:v1:refresh:*"); assertThat(keys).hasSize(1);
        String key=keys.iterator().next();
        Long before=redis.getExpire(key,TimeUnit.MILLISECONDS);
        assertThat(before).isPositive().isLessThanOrEqualTo(Duration.ofDays(14).toMillis());
        assertThat(redis.opsForHash().entries(key).values().toString()).doesNotContain(issued.refreshToken());
        var rotated=auth.refresh(issued.refreshToken());
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        assertThat(redis.opsForHash().size(key)).isEqualTo(3);
        redis.delete(key);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+rotated.accessToken())).andExpect(status().isUnauthorized());
    }
    @Test void unavailableRedisDoesNotAuthorize() throws Exception {
        active(); var issued=auth.login(email,password);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("simulated outage"))
                .when(redis).getExpire(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+issued.accessToken())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AUTH_STORE_UNAVAILABLE"));
    }
    @Test void requestLimitsAndEnglishErrorsAndHeaders() throws Exception {
        mvc.perform(get("/api/v1/me").header("Accept-Language","en-US,en;q=0.9"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Content-Language","en"))
                .andExpect(jsonPath("$.error.message").value("Check your authentication credentials."))
                .andExpect(header().string("X-Frame-Options","DENY"));
        mvc.perform(get("/api/v1/me").header("Accept-Language","fr"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Content-Language","ko"));
        mvc.perform(post("/api/v1/auth/signup").header("Accept-Language","en").contentType("application/json").content("x".repeat(65537)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
        var excessive=new ArrayList<AuthService.Consent>(consents); excessive.add(consents.getFirst());
        postJson("/auth/signup",Map.of("email",email,"password",password,"nickname","nickname","consents",excessive))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_user",Integer.class)).isZero();
    }
    @Test void malformedAndTamperedJwtIsRejected() throws Exception {
        active(); var issued=auth.login(email,password);
        String[] parts=issued.accessToken().split("\\.");
        String tampered=parts[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"other\"}".getBytes())+"."+parts[2];
        for(String token:List.of("invalid",tampered,issued.refreshToken()))
            mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void redisLuaSerializesCompetingRotationsWithoutDatabaseLock() throws Exception {
        String session=UUID.randomUUID().toString();
        refreshStore.create(session,"initial",clock.now.plus(Duration.ofDays(14)));
        try(var executor=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<Callable<Long>>();
            for(int i=0;i<24;i++) { String next="new-"+i; tasks.add(()->refreshStore.rotate(session,"initial",next)); }
            var outcomes=new ArrayList<Long>();
            for(var future:executor.invokeAll(tasks)) outcomes.add(future.get());
            assertThat(outcomes.stream().filter(v->v==1).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(v->v==2).count()).isEqualTo(23);
        }
    }
    @Test void signedTokensWithWrongClaimsAreRejected() throws Exception {
        active(); var issued=auth.login(email,password);
        var base=com.nimbusds.jwt.SignedJWT.parse(issued.accessToken()).getJWTClaimsSet();
        var claims=List.of(
                new com.nimbusds.jwt.JWTClaimsSet.Builder(base).issuer("wrong-issuer").build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder(base).audience("wrong-audience").build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder(base).expirationTime(null).build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder(base).expirationTime(Date.from(clock.now.plusSeconds(301))).build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder(base).claim("role","ADMIN").build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder(base).issueTime(Date.from(clock.now.plusSeconds(30))).build());
        for(var claim:claims) {
            var jwt=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS256)
                    .type(com.nimbusds.jose.JOSEObjectType.JWT).build(),claim);
            jwt.sign(new com.nimbusds.jose.crypto.MACSigner(signingKey));
            mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+jwt.serialize())).andExpect(status().isUnauthorized());
        }
    }
    @Test void swaggerDocumentsRealRoutesAndPreservesApiSecurity() throws Exception {
        var result=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("pueblo · member-service"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.csrfToken").doesNotExist()).andReturn();
        JsonNode document=mapper.readTree(result.getResponse().getContentAsString());
        assertThat(document.get("paths").size()).isEqualTo(10);
        assertThat(document.at("/paths/~1api~1v1~1auth~1jwks").isMissingNode()).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1me/get/security/0/bearerAuth").isArray()).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1auth~1login/post/security").isMissingNode()).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1auth~1csrf/get/parameters").isMissingNode()).isTrue();
        mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("requestInterceptor: async function")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/auth/csrf")));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.containsString("script-src 'self'")));
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script-src 'self'"))));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

}
