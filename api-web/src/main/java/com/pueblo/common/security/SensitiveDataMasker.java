package com.pueblo.common.security;

import java.util.regex.Pattern;

/** Defense in depth for text logs. Callers must still avoid logging request bodies or arbitrary personal data. */
public final class SensitiveDataMasker {
    private static final String KEYS="password|passwd|pwd|secret|client[_-]?secret|jwt[_-]?token|access[_-]?token|refresh[_-]?token|token|authorization|cookie|set-cookie|code|verification[_-]?code|email|phone|recipient|address[12]?|addressSnapshot|shipping";
    private static final Pattern FIELD=Pattern.compile("(?i)([\\\"']?(?:"+KEYS+")[\\\"']?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*+\\\"|'(?:\\\\.|[^'\\\\])*+'|[^,;&}\\r\\n]+)");
    private static final Pattern STRUCTURED=Pattern.compile("(?is)([\\\"']?(?:shipping|addressSnapshot|address)[\\\"']?\\s*[:=]\\s*)[\\[{].*");
    private static final Pattern HEADER=Pattern.compile("(?im)((?:authorization|(?:set-)?cookie)\\s*[:=]\\s*)[^\\r\\n]*");
    private static final Pattern BEARER=Pattern.compile("(?i)\\bBearer\\s+[^\\s,;\\\"']+");
    private static final Pattern JWT=Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern EMAIL=Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE=Pattern.compile("(?<![0-9])(?:\\+82[- .]?)?0?1[016789][- .]?[0-9]{3,4}[- .]?[0-9]{4}(?![0-9])");
    private static final Pattern URL_CREDENTIALS=Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+:[^\\s/@]+@");
    private SensitiveDataMasker() { }
    public static String mask(String message) {
        if(message==null) return "";
        // Bound regex work and prevent multi-line log forgery. Truncate before processing.
        String value=message.length()>16384?message.substring(0,16384)+"[TRUNCATED]":message;
        value=STRUCTURED.matcher(value).replaceAll("$1[REDACTED]");
        value=HEADER.matcher(value).replaceAll("$1[REDACTED]");
        value=FIELD.matcher(value).replaceAll("$1[REDACTED]");
        value=BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        value=JWT.matcher(value).replaceAll("[REDACTED]");
        value=EMAIL.matcher(value).replaceAll("[REDACTED]");
        value=PHONE.matcher(value).replaceAll("[REDACTED]");
        value=URL_CREDENTIALS.matcher(value).replaceAll("$1[REDACTED]@");
        return value.replace('\r',' ').replace('\n',' ');
    }
}
