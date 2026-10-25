package com.pueblo.member.config;

import com.pueblo.member.auth.AuthProperties;
import com.pueblo.member.mail.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class MailConfiguration {
    @Bean MailDelivery mailDelivery(AuthProperties properties, ObjectProvider<JavaMailSender> provider) {
        if ("mock".equals(properties.mailMode())) return new RecordingMailDelivery();
        if (!"smtp".equals(properties.mailMode())) throw new IllegalArgumentException("Unknown mail mode");
        JavaMailSender sender = provider.getIfAvailable();
        if (sender == null) throw new IllegalStateException("SMTP configuration is required");
        return new SmtpMailDelivery(sender, properties.mailFrom());
    }
}
