package com.pueblo.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.common.header.Headers;

public final class HmacMessageSignature {
    private final byte[] key;
    public HmacMessageSignature(String secret) {
        key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("Message signing key requires at least 32 UTF-8 bytes");
    }
    public byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
    public void verify(String body, Headers headers, String headerName) {
        if (body == null || body.length() > 65536 || body.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
        var values = headers.headers(headerName).iterator();
        if (!values.hasNext()) throw invalid();
        byte[] value = values.next().value();
        if (value == null || value.length != 64 || values.hasNext() || !MessageDigest.isEqual(sign(body), value)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid authenticated message"); }
}
