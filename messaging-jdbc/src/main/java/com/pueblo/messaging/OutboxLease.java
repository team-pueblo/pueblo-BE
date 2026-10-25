package com.pueblo.messaging;

/** Owner is unique for every claim, including retries by the same application. */
public record OutboxLease(OutboxRecord record, String owner, int attempts) { }
