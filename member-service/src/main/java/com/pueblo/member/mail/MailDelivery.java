package com.pueblo.member.mail;

@FunctionalInterface
public interface MailDelivery {
    void send(String recipient, MailContent content);
}
