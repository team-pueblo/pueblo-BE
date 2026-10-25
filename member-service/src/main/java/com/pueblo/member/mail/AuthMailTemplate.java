package com.pueblo.member.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

public final class AuthMailTemplate {
    private static final String TEMPLATE = load();
    private AuthMailTemplate() { }

    public static MailContent render(String purpose, String link) {
        boolean verify = switch (purpose) {
            case "VERIFY" -> true;
            case "RESET" -> false;
            default -> throw new IllegalArgumentException("Unknown authentication mail purpose");
        };
        URI uri = URI.create(link);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("HTTP(S) authentication link required");
        }
        String title = verify ? "이메일 인증" : "비밀번호 재설정";
        String intro = verify ? "pueblo에 오신 것을 환영합니다." : "비밀번호 재설정을 요청하셨나요?";
        String description = verify ? "아래 버튼을 눌러 이메일 인증을 완료해 주세요. 인증을 마치면 로그인할 수 있습니다."
                : "아래 버튼을 눌러 새로운 비밀번호를 설정해 주세요. 변경 후에는 다시 로그인해 주세요.";
        String action = verify ? "이메일 인증하기" : "비밀번호 재설정하기";
        String lifetime = verify ? "발급 후 30분" : "발급 후 15분";
        String note = verify ? "본인이 가입을 요청하지 않았다면 이 메일을 무시해 주세요."
                : "본인이 요청하지 않았다면 이 메일을 무시해 주세요. 기존 비밀번호는 변경되지 않습니다.";
        String html = TEMPLATE.replace("{{title}}", title).replace("{{intro}}", intro)
                .replace("{{description}}", description).replace("{{action}}", action)
                .replace("{{lifetime}}", lifetime).replace("{{note}}", note)
                .replace("{{link}}", HtmlUtils.htmlEscape(link, StandardCharsets.UTF_8.name()));
        String text = "pueblo\n\n" + title + "\n\n" + intro + "\n" + description
                + "\n\n유효시간: " + lifetime + "\n인증 링크는 한 번만 사용할 수 있습니다."
                + "\n" + note + "\n\n" + action + ":\n" + link;
        return new MailContent("pueblo " + title, text, html);
    }

    private static String load() {
        try {
            return new ClassPathResource("mail/auth.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Authentication email template unavailable", ex);
        }
    }
}
