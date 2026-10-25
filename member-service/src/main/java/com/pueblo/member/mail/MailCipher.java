package com.pueblo.member.mail;

import com.pueblo.member.auth.AuthProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class MailCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public MailCipher(AuthProperties properties) throws Exception {
        try (var stream = properties.mailKeyLocation().getInputStream()) {
            byte[] bytes = Base64.getDecoder().decode(new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim());
            if (bytes.length != 32) throw new IllegalArgumentException("Mail encryption key must be 256 bits");
            key = new SecretKeySpec(bytes, "AES");
        }
    }
    public byte[] encrypt(String value, String jobId) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(jobId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (Exception ex) { throw new IllegalStateException("Mail encryption failed"); }
    }
    public String decrypt(byte[] bytes, String jobId) {
        try {
            ByteBuffer input = ByteBuffer.wrap(bytes); byte[] iv = new byte[12]; input.get(iv);
            byte[] encrypted = new byte[input.remaining()]; input.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(jobId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Mail decryption failed"); }
    }
}
