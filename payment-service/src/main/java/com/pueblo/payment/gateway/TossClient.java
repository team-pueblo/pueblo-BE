package com.pueblo.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-key only. Production activation requires the order/inventory/refund consumer first. */
public final class TossClient {
    private final JsonHttp http; private final String base,authorization;
    public TossClient(JsonHttp http,String base,String secret,boolean mock) {
        if(secret==null||!secret.matches("test_(?:g)?sk_[A-Za-z0-9_-]{8,200}")) throw new IllegalArgumentException("A Toss test secret key is required");
        if(mock) {
            var uri=java.net.URI.create(base);
            if(!"http".equals(uri.getScheme())||!Set.of("127.0.0.1","localhost").contains(uri.getHost())||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().isEmpty())
                throw new IllegalArgumentException("Mock payment endpoint must be loopback HTTP");
        } else if(!"https://api.tosspayments.com".equals(base)) throw new IllegalArgumentException("Invalid Toss endpoint");
        this.http=http; this.base=base;
        authorization="Basic "+Base64.getEncoder().encodeToString((secret+":").getBytes(StandardCharsets.UTF_8));
    }
    public JsonHttp.Reply confirm(UUID orderId,String paymentKey,long amount,UUID paymentId) {
        return http.call(base,"POST","/v1/payments/confirm",Map.of("Authorization",authorization,"Idempotency-Key",paymentId.toString()),
                Map.of("orderId",orderId.toString(),"paymentKey",paymentKey,"amount",amount));
    }
    public JsonHttp.Reply find(UUID orderId) {
        return http.call(base,"GET","/v1/payments/orders/"+orderId,Map.of("Authorization",authorization),null);
    }
    /** Never considers HTTP 200 alone to be an approved, matching payment. */
    public static String outcome(JsonHttp.Reply reply,UUID orderId,String paymentKey,long amount) {
        JsonNode p=reply.body();
        if(reply.status()!=200||p==null||!p.isObject()) return "UNKNOWN";
        if(!orderId.toString().equals(p.path("orderId").asText())||!paymentKey.equals(p.path("paymentKey").asText())
                ||!"KRW".equals(p.path("currency").asText())||!p.path("totalAmount").isIntegralNumber()
                ||!p.path("totalAmount").canConvertToLong()||p.path("totalAmount").longValue()!=amount) return "REVIEW_REQUIRED";
        return switch(p.path("status").asText()) {
            case "DONE" -> p.path("balanceAmount").isIntegralNumber()&&p.path("balanceAmount").canConvertToLong()&&p.path("balanceAmount").longValue()==amount ? "SUCCEEDED":"REVIEW_REQUIRED";
            case "ABORTED","EXPIRED" -> "FAILED";
            case "READY","IN_PROGRESS","WAITING_FOR_DEPOSIT" -> "UNKNOWN";
            default -> "REVIEW_REQUIRED";
        };
    }
}
