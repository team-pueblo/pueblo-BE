package com.pueblo.member.auth;

import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import com.pueblo.common.security.redis.SecurityKeyNamespace;
import com.pueblo.common.security.redis.SecurityKeyPurpose;
import com.pueblo.common.security.redis.SecurityTtlPolicy;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Only hashed refresh credentials. Absolute expiry never extends during rotation. */
@Component
public class RedisRefreshTokens {
    private final StringRedisTemplate redis;
    private final SecurityKeyNamespace namespace;
    private final Clock clock;
    private static final DefaultRedisScript<Long> CREATE=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1]) == 1 then return 0 end
        redis.call('HSET',KEYS[1],'current',ARGV[1],'rotations','0')
        redis.call('PEXPIREAT',KEYS[1],ARGV[2])
        return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> ROTATE=new DefaultRedisScript<>("""
        local current=redis.call('HGET',KEYS[1],'current')
        if not current then return 0 end
        local used=redis.call('HGET',KEYS[1],'used:'..ARGV[1])
        if used then
          if tonumber(ARGV[3])-tonumber(used) < 3000 then return 2 end
          redis.call('DEL',KEYS[1]); return 3
        end
        if current ~= ARGV[1] then return 0 end
        if tonumber(redis.call('HGET',KEYS[1],'rotations')) >= 4096 then
          redis.call('DEL',KEYS[1]); return 3
        end
        redis.call('HSET',KEYS[1],'current',ARGV[2],'used:'..ARGV[1],ARGV[3])
        redis.call('HINCRBY',KEYS[1],'rotations',1)
        return 1
        """,Long.class);
    public RedisRefreshTokens(StringRedisTemplate redis,Clock clock,@Value("${pueblo.auth.redis-namespace}") String namespace) {
        this.redis=redis; this.clock=clock; this.namespace=new SecurityKeyNamespace(namespace,"member");
    }
    public String key(String sessionId) { return namespace.key(SecurityKeyPurpose.REFRESH_SESSION,sessionId); }
    private static String hash(String raw) { return HexFormat.of().formatHex(TokenSecrets.hash(raw)); }
    public void create(String sessionId,String raw,Instant expires) {
        try {
            Long result=redis.execute(CREATE,List.of(key(sessionId)),hash(raw),Long.toString(SecurityTtlPolicy.refreshExpiryMillis(expires,clock)));
            if(!Long.valueOf(1).equals(result)) throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE);
        } catch(org.springframework.dao.DataAccessException ex) { throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE); }
    }
    public long rotate(String sessionId,String oldToken,String newToken) {
        try {
            Long result=redis.execute(ROTATE,List.of(key(sessionId)),hash(oldToken),hash(newToken),Long.toString(clock.millis()));
            if(result==null) throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE);
            return result;
        } catch(org.springframework.dao.DataAccessException ex) { throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE); }
    }
    public boolean isActive(String sessionId) {
        try {
            Long ttl=redis.getExpire(key(sessionId),java.util.concurrent.TimeUnit.MILLISECONDS);
            return SecurityTtlPolicy.active(ttl);
        } catch(org.springframework.dao.DataAccessException ex) { throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE); }
    }
    public void delete(String sessionId) {
        try { redis.delete(key(sessionId)); }
        catch(org.springframework.dao.DataAccessException ex) { throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE); }
    }
}
