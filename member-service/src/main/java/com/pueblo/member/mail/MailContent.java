package com.pueblo.member.mail;

public record MailContent(String subject, String text, String html) {
    @Override public String toString() { return "MailContent[redacted]"; }
}
