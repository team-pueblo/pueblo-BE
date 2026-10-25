package com.pueblo.member.mail;

import java.nio.charset.StandardCharsets;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.core.io.ClassPathResource;

public class SmtpMailDelivery implements MailDelivery {
    private final JavaMailSender sender;
    private final String from;

    public SmtpMailDelivery(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override public void send(String recipient, MailContent content) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
            helper.addInline("pueblo-logo", new ClassPathResource("mail/images/pueblo_logo.png"), "image/png");
            sender.send(message);
        } catch (MessagingException ex) {
            throw new MailPreparationException("Could not prepare authentication email");
        }
    }
}
