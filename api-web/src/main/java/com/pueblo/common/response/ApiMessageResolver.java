package com.pueblo.common.response;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

public final class ApiMessageResolver {
    private static final Set<String> VALIDATION_CODES=Set.of("NotNull","NotBlank","NotEmpty","Size","Min","Max","DecimalMin","DecimalMax","Email","Pattern","AssertTrue","Positive","PositiveOrZero","typeMismatch","required");
    public Locale locale(HttpServletRequest request) {
        String header=request.getHeader("Accept-Language");
        if(header!=null) try {
            var ranges=Locale.LanguageRange.parse(header).stream().filter(range->range.getWeight()>0).toList();
            Locale matched=Locale.lookup(ranges,List.of(Locale.KOREAN,Locale.ENGLISH));
            if(matched!=null) return matched;
        } catch(IllegalArgumentException ignored) { }
        return Locale.KOREAN;
    }
    public String resolve(String key,Locale locale) {
        var bundle=ResourceBundle.getBundle("messages.errors",locale,ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        return bundle.containsKey(key)?bundle.getString(key):bundle.getString("INTERNAL_ERROR");
    }
    public ApiErrorDetail detail(String field,String validationCode,Locale locale) {
        String code=VALIDATION_CODES.contains(validationCode==null?"":validationCode)?validationCode:"invalid";
        String safeField=field!=null && field.matches("[A-Za-z0-9_.\\[\\]]{1,128}")?field:"request";
        return new ApiErrorDetail(safeField,code,resolve("validation."+code,locale));
    }
}
