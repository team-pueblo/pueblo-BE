package com.pueblo.payment.gateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class JsonHttpTest {
    @Test void redirectsDoNotForwardSecretsToAnotherEndpoint() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var forwarded=new AtomicInteger();
        server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","/target");byte[] b="{}".getBytes();e.sendResponseHeaders(302,b.length);e.getResponseBody().write(b);e.close();});
        server.createContext("/target",e->{forwarded.incrementAndGet();e.sendResponseHeaders(200,0);e.close();});server.start();
        try { var r=new JsonHttp(new ObjectMapper()).call("http://127.0.0.1:"+server.getAddress().getPort(),"GET","/redirect",Map.of("Authorization","Basic fake"),null);
            assertThat(r.status()).isEqualTo(302);assertThat(forwarded.get()).isZero();
        } finally {server.stop(0);}
    }
    @Test void oversizedResponseFailsWithoutExposingItsContents() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/large",e->{byte[] b=new byte[262145];e.sendResponseHeaders(200,b.length);try {e.getResponseBody().write(b);}finally {e.close();}});server.start();
        try { assertThatThrownBy(()->new JsonHttp(new ObjectMapper()).call("http://127.0.0.1:"+server.getAddress().getPort(),"GET","/large",Map.of(),null)).isInstanceOf(JsonHttp.Unavailable.class).hasMessage("Upstream response unavailable"); }
        finally {server.stop(0);}
    }
}
