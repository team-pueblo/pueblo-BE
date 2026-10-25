package com.pueblo.member.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

@ConfigurationProperties("pueblo.auth")
public record AuthProperties(
        Resource mailKeyLocation,
        @DefaultValue("pueblo-member") String issuer,
        @DefaultValue("pueblo-api") String audience,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("Lax") String cookieSameSite,
        @DefaultValue("https://localhost") String frontendBaseUrl,
        @DefaultValue("v1") String termsVersion,
        @DefaultValue("v1") String privacyVersion,
        List<String> allowedOrigins,
        @DefaultValue("smtp") String mailMode,
        @DefaultValue("noreply@localhost") String mailFrom) {
    public AuthProperties {
        com.pueblo.api.ExternalUrlPolicy.requireWebUrl(frontendBaseUrl,!cookieSecure,false);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (!List.of("Lax", "Strict", "None").contains(cookieSameSite)
                || (!cookieSecure && "None".equals(cookieSameSite))) {
            throw new IllegalArgumentException("Invalid cookie policy");
        }
        allowedOrigins.forEach(origin -> com.pueblo.api.ExternalUrlPolicy.requireWebUrl(origin,!cookieSecure,true));
        if (allowedOrigins.stream().anyMatch(v -> v.contains("*"))) {
            throw new IllegalArgumentException("Explicit origins required");
        }
    }
    public String refreshCookieName() { return cookieSecure ? "__Secure-pueblo_refresh" : "pueblo_refresh"; }
    public String csrfCookieName() { return cookieSecure ? "__Host-pueblo_csrf" : "pueblo_csrf"; }
}
