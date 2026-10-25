package com.pueblo.common.security.validation;

import java.net.URI;
import java.util.Set;

/** Configuration URLs only. Never fetch user-controlled URLs without a separate SSRF policy. */
public final class ExternalUrlPolicy {
    private ExternalUrlPolicy() { }
    public static URI requireWebUrl(String value,boolean allowLocalHttp,boolean originOnly) {
        try {
            URI uri=URI.create(value);
            String host=uri.getHost();
            boolean local=host!=null && Set.of("localhost","127.0.0.1","[::1]").contains(host);
            if(host==null || uri.getRawUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null
                    || (!"https".equals(uri.getScheme()) && !(allowLocalHttp && local && "http".equals(uri.getScheme())))
                    || uri.getPort()>65535 || uri.getPort()==0 || uri.getPort() < -1
                    || (originOnly && uri.getRawPath()!=null && !uri.getRawPath().isEmpty())) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException ex) { throw new IllegalArgumentException("An absolute HTTPS URL without credentials, query or fragment is required"); }
    }
}
