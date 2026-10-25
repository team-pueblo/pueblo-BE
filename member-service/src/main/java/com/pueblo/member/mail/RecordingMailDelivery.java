package com.pueblo.member.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicit test adapter. No HTTP endpoint or token logging. */
public class RecordingMailDelivery implements MailDelivery {
    public record Message(String recipient, MailContent content) {
        public String body() { return content.text(); }
        @Override public String toString() { return "Message[redacted]"; }
    }
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    @Override public void send(String recipient, MailContent content) {
        if (messages.size() >= 100) messages.removeFirst();
        messages.add(new Message(recipient, content));
    }
    public List<Message> messages() { return List.copyOf(messages); }
    public void clear() { messages.clear(); }
}
