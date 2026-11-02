package com.pueblo.payment.gateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TossClientTest {
    private final JsonHttp http=new JsonHttp(new ObjectMapper());
    @Test void liveKeysAndUntrustedEndpointsFailClosed() {
        assertThatThrownBy(()->new TossClient(http,"https://api.tosspayments.com","live_sk_not_a_real_key",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TossClient(http,"https://example.com","test_sk_not_a_real_key",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TossClient(http,"http://example.com","test_sk_not_a_real_key",true)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->new TossClient(http,"https://api.tosspayments.com","test_gsk_not_a_real_key",false)).doesNotThrowAnyException();
    }
    @Test void upstreamErrorsRemainUnknownIncludingNotFound() {
        for(int status:new int[]{400,401,404,409,429,500,302})
            assertThat(TossClient.outcome(new JsonHttp.Reply(status,new ObjectMapper().createObjectNode()),java.util.UUID.randomUUID(),"key",1000)).isEqualTo("UNKNOWN");
    }
    @Test void serviceEndpointRejectsUserInfoPathsAndForeignPlainHttp() {
        for(String url:new String[]{"http://example.com","http://localhost/else","https://user@example.com","http://localhost?key=x"})
            assertThatThrownBy(()->JsonHttp.serviceUrl(url,"order-service")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void widgetKeyLookupUsesEncodedPaymentKeyAsOnePathSegment() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var path=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/",exchange->{
            path.set(exchange.getRequestURI().getRawPath());
            byte[] bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            new TossClient(http,"http://127.0.0.1:"+server.getAddress().getPort(),"test_gsk_fixture_key",true).find("key/a?b#c+d");
            assertThat(path.get()).isEqualTo("/v1/payments/key%2Fa%3Fb%23c%2Bd");
        } finally {server.stop(0);}
    }

}
