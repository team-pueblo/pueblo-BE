package com.pueblo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;

public final class ApiErrors {
    private ApiErrors() { }
    public static Locale locale(HttpServletRequest request) {
        String header=request.getHeader("Accept-Language");
        if(header!=null) try {
            Locale match=Locale.lookup(Locale.LanguageRange.parse(header),List.of(Locale.KOREAN,Locale.ENGLISH));
            if(match!=null) return match;
        } catch(IllegalArgumentException ignored) { }
        return Locale.KOREAN;
    }
    public static Map<String,Object> body(ErrorCode code, HttpServletRequest request) {
        var bundle=ResourceBundle.getBundle("messages.errors",locale(request),ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        return Map.of("error",Map.of("code",code.name(),"message",bundle.getString(code.name())),"requestId",RequestIdFilter.id(request));
    }
    public static void write(ObjectMapper mapper,HttpServletRequest request,HttpServletResponse response,ErrorCode code) throws IOException {
        response.setStatus(code.status().value()); response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Content-Language",locale(request).getLanguage());
        response.addHeader("Vary","Accept-Language"); response.setHeader("Cache-Control","no-store");
        mapper.writeValue(response.getOutputStream(),body(code,request));
    }
}
