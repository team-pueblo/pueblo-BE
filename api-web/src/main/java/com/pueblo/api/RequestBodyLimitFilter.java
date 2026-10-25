package com.pueblo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE+1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES=65536;
    private final ObjectMapper mapper;
    public RequestBodyLimitFilter(ObjectMapper mapper) { this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getContentLengthLong()>MAX_BYTES) { ApiErrors.write(mapper,request,response,ErrorCode.PAYLOAD_TOO_LARGE); return; }
        byte[] body=request.getInputStream().readNBytes(MAX_BYTES+1);
        if(body.length>MAX_BYTES) { ApiErrors.write(mapper,request,response,ErrorCode.PAYLOAD_TOO_LARGE); return; }
        try { chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read() { return input.read(); }
                    public boolean isFinished() { return input.available()==0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON requests only"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
        },response); } catch(ApiException ex) {
            if(response.isCommitted()) throw ex;
            response.resetBuffer();
            ApiErrors.write(mapper,request,response,ex.code());
        }
    }
}
