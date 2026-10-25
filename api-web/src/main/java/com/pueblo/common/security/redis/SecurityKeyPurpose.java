package com.pueblo.common.security.redis;



public enum SecurityKeyPurpose {
    REFRESH_SESSION("refresh");
    private final String segment;
    SecurityKeyPurpose(String segment) { this.segment=segment; }
    public String segment() { return segment; }
}
