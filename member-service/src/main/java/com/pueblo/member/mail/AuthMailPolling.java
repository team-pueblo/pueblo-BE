package com.pueblo.member.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="pueblo.auth", name="mail-polling-enabled", havingValue="true", matchIfMissing=true)
public class AuthMailPolling {
    private final AuthMailJobs jobs;
    public AuthMailPolling(AuthMailJobs jobs) { this.jobs = jobs; }
    @Scheduled(fixedDelayString="${pueblo.auth.mail-poll-interval-ms:1000}")
    public void poll() { for (int i=0; i<5 && !Thread.currentThread().isInterrupted(); i++) if (!jobs.dispatchOne()) return; }
}
