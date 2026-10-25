package com.pueblo.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.pueblo.common.exception.*;
import java.util.*;

public final class ServiceClients {
    private final JsonHttp http; private final String member,order,secret;
    public ServiceClients(JsonHttp http,String member,String order,String secret) {
        this.http=http; this.member=JsonHttp.serviceUrl(member,"member-service"); this.order=JsonHttp.serviceUrl(order,"order-service");
        if(secret==null||!secret.matches("[!-~]{32,256}")) throw new IllegalArgumentException("Order service credential required");
        this.secret=secret;
    }
    public UUID member(String bearer) {
        try { return UUID.fromString(read(http.call(member,"GET","/api/v1/me",Map.of("Authorization",bearer),null)).path("id").asText()); }
        catch(BusinessException e) { throw e; }
        catch(RuntimeException e) { throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE); }
    }
    public JsonNode order(UUID id,String bearer) {
        return invoke("GET","/api/v1/me/orders/"+id,Map.of("Authorization",bearer),null);
    }
    public JsonNode begin(UUID id,UUID paymentId,long amount,String bearer) {
        return invoke("POST","/internal/v1/orders/"+id+"/payment-attempts",Map.of("Authorization",bearer,"X-Payment-Service-Token",secret),Map.of("paymentId",paymentId,"amount",amount,"currency","KRW"));
    }
    public JsonNode attempt(UUID id) {
        return invoke("GET","/internal/v1/orders/"+id+"/payment-attempts",Map.of("X-Payment-Service-Token",secret),null);
    }
    private JsonNode invoke(String method,String path,Map<String,String> headers,Object body) {
        try { return read(http.call(order,method,path,headers,body)); }
        catch(BusinessException e) { throw e; }
        catch(RuntimeException e) { throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    private JsonNode read(JsonHttp.Reply reply) {
        if(reply.status()==401||reply.status()==403) throw BusinessException.unauthorized();
        if(reply.status()==404) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        if(reply.status()==409) throw new BusinessException(com.pueblo.payment.checkout.PaymentErrorCode.PAYMENT_ORDER_CONFLICT);
        if(reply.status()!=200||reply.body()==null||!reply.body().path("data").isObject()) throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        return reply.body().path("data");
    }
}
