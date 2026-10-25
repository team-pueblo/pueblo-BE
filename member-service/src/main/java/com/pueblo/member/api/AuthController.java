package com.pueblo.member.api;

import com.pueblo.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import com.pueblo.member.auth.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name="회원 인증")
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    private final AuthProperties properties;
    private final Clock clock;
    public AuthController(AuthService auth, AuthProperties properties, Clock clock) {
        this.auth=auth; this.properties=properties; this.clock=clock;
    }
    public record CsrfData(String token,String headerName) { }
    public record MessageData(String message) { }
    public record CompletionData(boolean completed) { }
    public record TokenData(String accessToken,String tokenType,long expiresIn,AuthService.UserView user) { }
    public record Signup(@NotBlank @Size(max=254) String email, @Schema(format="password",accessMode=Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(min=10,max=128) String password,
            @NotBlank @Size(max=50) String nickname, @NotNull @Size(min=2,max=2) List<AuthService.@NotNull Consent> consents) {
        @Override public String toString() { return "Signup[redacted]"; }
    }
    public record Login(@NotBlank @Size(max=254) String email, @Schema(format="password",accessMode=Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(max=128) String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Email(@NotBlank @Size(max=254) String email) { }
    public record Link(@NotBlank @Size(min=43,max=43) String token) {
        @Override public String toString() { return "Link[redacted]"; }
    }
    public record Reset(@NotBlank @Size(min=43,max=43) String token, @Schema(format="password",accessMode=Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(min=10,max=128) String newPassword) {
        @Override public String toString() { return "Reset[redacted]"; }
    }
    @Operation(summary="CSRF 토큰 발급")
    @GetMapping("/auth/csrf")
    ApiResponse<CsrfData> csrf(@Parameter(hidden=true) CsrfToken token, HttpServletRequest request) {
        return ApiResponse.of(new CsrfData(token.getToken(),token.getHeaderName()),request);
    }
    @Operation(summary="이메일 인증 대기 회원 가입")
    @PostMapping("/auth/signup") @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<MessageData> signup(@Valid @RequestBody Signup body, HttpServletRequest request) {
        auth.signup(body.email(),body.password(),body.nickname(),body.consents()); return accepted(request);
    }
    @Operation(summary="이메일 인증 링크 재요청")
    @PostMapping("/auth/email-verification-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<MessageData> resend(@Valid @RequestBody Email body, HttpServletRequest request) {
        auth.requestVerification(body.email()); return accepted(request);
    }
    @Operation(summary="이메일 인증 완료")
    @PostMapping("/auth/email-verifications")
    ApiResponse<CompletionData> verify(@Valid @RequestBody Link body, HttpServletRequest request) {
        auth.verifyEmail(body.token()); return done(request);
    }
    @Operation(summary="비밀번호 재설정 링크 요청")
    @PostMapping("/auth/password-reset-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<MessageData> resetRequest(@Valid @RequestBody Email body, HttpServletRequest request) {
        auth.requestPasswordReset(body.email()); return accepted(request);
    }
    @Operation(summary="비밀번호 변경 및 모든 세션 폐기")
    @PostMapping("/auth/password-resets")
    ApiResponse<CompletionData> reset(@Valid @RequestBody Reset body, HttpServletRequest request) {
        auth.resetPassword(body.token(),body.newPassword()); return done(request);
    }
    @Operation(summary="로그인 및 토큰 발급")
    @PostMapping("/auth/login")
    ApiResponse<TokenData> login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        return result(auth.login(body.email(),body.password()),request,response);
    }
    @Operation(summary="Refresh Token 교체")
    @PostMapping("/auth/refresh")
    ApiResponse<TokenData> refresh(HttpServletRequest request, HttpServletResponse response) {
        return result(auth.refresh(refreshCookie(request)),request,response);
    }
    @Operation(summary="현재 세션 로그아웃")
    @PostMapping("/auth/logout")
    ApiResponse<CompletionData> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(refreshCookie(request)); cookie(response,"",Duration.ZERO); return done(request);
    }
    @Operation(summary="현재 회원 조회")
    @SecurityRequirement(name="bearerAuth")
    @GetMapping("/me")
    ApiResponse<AuthService.UserView> me(@Parameter(hidden=true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) { return ApiResponse.of(auth.me(jwt.getSubject()),request); }
    private ApiResponse<TokenData> result(AuthService.Tokens tokens, HttpServletRequest request, HttpServletResponse response) {
        Duration remaining=Duration.between(clock.instant(),tokens.sessionExpiresAt());
        cookie(response,tokens.refreshToken(),remaining);
        return ApiResponse.of(new TokenData(tokens.accessToken(),"Bearer",Math.min(300,Math.max(0,remaining.toSeconds())),tokens.user()),request);
    }
    private void cookie(HttpServletResponse response, String value, Duration lifetime) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(properties.refreshCookieName(),value)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite(properties.cookieSameSite())
                .path("/api/v1/auth").maxAge(lifetime).build().toString());
    }
    private String refreshCookie(HttpServletRequest request) {
        if (request.getCookies()==null) return null;
        return Arrays.stream(request.getCookies()).filter(c->c.getName().equals(properties.refreshCookieName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }
    private ApiResponse<MessageData> accepted(HttpServletRequest request) { return ApiResponse.of(new MessageData("요청을 접수했습니다."),request); }
    private ApiResponse<CompletionData> done(HttpServletRequest request) { return ApiResponse.of(new CompletionData(true),request); }
}
