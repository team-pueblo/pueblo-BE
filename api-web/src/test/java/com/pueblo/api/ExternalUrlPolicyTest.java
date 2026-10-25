package com.pueblo.api;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ExternalUrlPolicyTest {
    @Test void allowsHttpsAndExplicitLocalHttpOnly() {
        assertThat(ExternalUrlPolicy.requireWebUrl("https://shop.example",false,true).getHost()).isEqualTo("shop.example");
        assertThat(ExternalUrlPolicy.requireWebUrl("http://localhost:5173",true,true).getHost()).isEqualTo("localhost");
        for(String url:new String[]{"javascript:alert(1)","//evil.example","https://user:password@shop.example","https://shop.example#fragment","https://shop.example?redirect=evil","http://shop.example","http://127.0.0.1:8080"})
            assertThatThrownBy(()->ExternalUrlPolicy.requireWebUrl(url,false,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExternalUrlPolicy.requireWebUrl("https://shop.example/path",false,true)).isInstanceOf(IllegalArgumentException.class);
    }
}
