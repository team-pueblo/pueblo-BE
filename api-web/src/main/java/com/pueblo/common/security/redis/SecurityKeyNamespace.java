package com.pueblo.common.security.redis;

import java.util.UUID;

/** Pure key policy: no Redis connection or shared service database credentials. */
public final class SecurityKeyNamespace {
    private final String namespace;
    public SecurityKeyNamespace(String namespace,String service) {
        if(service==null || !service.matches("[a-z][a-z0-9-]{0,31}") || namespace==null
                || !namespace.matches("pueblo:[a-z0-9-]{1,32}:"+java.util.regex.Pattern.quote(service)+":security:v1"))
            throw new IllegalArgumentException("Environment-specific security namespace required");
        this.namespace=namespace;
    }
    public String key(SecurityKeyPurpose purpose,String id) {
        return namespace+":"+purpose.segment()+":{"+UUID.fromString(id)+"}";
    }
}
