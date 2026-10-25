package com.pueblo.member.mail;

import java.util.Properties;
import jakarta.mail.Session;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthMailTemplateTest {
    @Test void rendersBothPurposesWithEscapedLinksAndNoUnresolvedPlaceholders() {
        String link = "https://shop.example/verify-email?source=mail&label='test'#token=synthetic";
        for (String purpose : new String[]{"VERIFY", "RESET"}) {
            var content = AuthMailTemplate.render(purpose, link);
            assertThat(content.html()).doesNotContain("{{").contains("&amp;", "&#39;");
            assertThat(content.text()).endsWith(link);
            assertThat(content.toString()).doesNotContain(link);
        }
        assertThat(AuthMailTemplate.render("VERIFY", "https://shop.example/#token=test").text()).contains("30");
        assertThat(AuthMailTemplate.render("RESET", "https://shop.example/#token=test").text()).contains("15");
        assertThatThrownBy(() -> AuthMailTemplate.render("VERIFY", "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthMailTemplate.render("UNKNOWN", "https://shop.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void smtpSendsUtf8MultipartAlternativeWithoutExternalNetwork() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        var content = AuthMailTemplate.render("VERIFY", "https://shop.example/verify-email#token=synthetic");
        new SmtpMailDelivery(sender, "noreply@example.com").send("recipient@example.com", content);
        message.saveChanges();
        verify(sender).send(message);
        assertThat(message.getSubject()).isEqualTo(content.subject());
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("recipient@example.com");
        assertThat(findPart(message, "text/plain")).isEqualTo(content.text());
        assertThat(findPart(message, "text/html")).isEqualTo(content.html());
        assertThat(content.html()).contains("src=\"cid:pueblo-logo\"");
        Part logo = findImage(message);
        assertThat(logo).isNotNull();
        assertThat(logo.getDisposition()).isEqualTo(Part.INLINE);
        assertThat(logo.getHeader("Content-ID")).containsExactly("<pueblo-logo>");
        try (var stream = logo.getInputStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(new org.springframework.core.io.ClassPathResource(
                    "mail/images/pueblo_logo.png").getContentAsByteArray());
        }
    }

    private Part findImage(Part part) throws Exception {
        if (part.isMimeType("image/png")) return part;
        if (part.getContent() instanceof Multipart multipart) {
            for (int i=0; i<multipart.getCount(); i++) {
                Part found = findImage(multipart.getBodyPart(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private String findPart(Part part, String type) throws Exception {
        if (part.isMimeType(type)) return (String) part.getContent();
        if (part.getContent() instanceof Multipart multipart) {
            for (int i=0; i<multipart.getCount(); i++) {
                String found = findPart(multipart.getBodyPart(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
