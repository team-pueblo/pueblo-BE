package com.pueblo.member.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class TokenSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private TokenSecrets() { }
    public static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static byte[] hash(String raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
