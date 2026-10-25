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
}
