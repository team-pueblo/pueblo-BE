package com.pueblo.common.security;

import com.pueblo.common.exception.ErrorCode;
import com.pueblo.common.logging.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;

public final class SecurityEventLogger {
    private SecurityEventLogger() { }
    public static void error(HttpServletRequest request,ErrorCode code) {
        int status=code.status().value();
        if(status==401 || status==403 || status==429 || code.code().equals("AUTH_STORE_UNAVAILABLE"))
            LoggerFactory.getLogger(SecurityEventLogger.class).warn("Security request rejected requestId={} errorType={} status={}",
                    RequestIdFilter.id(request),code.code(),status);
    }
}
