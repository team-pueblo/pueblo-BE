package com.pueblo.common.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Order(Ordered.HIGHEST_PRECEDENCE+1)
public final class RequestLoggingFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/actuator/health")) { chain.doFilter(request,response); return; }
        long started=System.nanoTime(); boolean completed=false;
        try { chain.doFilter(request,response); completed=true; }
        finally {
            Object route=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String method=java.util.Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE").contains(request.getMethod())?request.getMethod():"OTHER";
            LoggerFactory.getLogger(getClass()).info("HTTP request requestId={} method={} route={} status={} elapsedMs={}",
                    RequestIdFilter.id(request),method,route==null?"unmatched":route.toString(),completed?response.getStatus():500,(System.nanoTime()-started)/1_000_000);
        }
    }
}
