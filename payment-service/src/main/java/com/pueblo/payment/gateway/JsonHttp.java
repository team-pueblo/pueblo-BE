package com.pueblo.payment.gateway;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Bounded JSON transport. Never retains upstream exception messages, headers or response bodies. */
public final class JsonHttp {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper;
    public JsonHttp(ObjectMapper mapper) { this.mapper=mapper; }
    public record Reply(int status,JsonNode body) { }
    public Reply call(String base,String method,String path,Map<String,String> headers,Object body) {
        try {
            var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
            headers.forEach(b::header);
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            // A byte-array subscriber consumes at most 256 KiB, while retaining HttpClient's full response timeout.
            var response=client.send(b.build(),info->new LimitedSubscriber(262144));
            return new Reply(response.statusCode(),mapper.readTree(response.body()));
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new Unavailable(); }
        catch(Exception ex) { throw new Unavailable(); }
    }
    public static final class Unavailable extends RuntimeException { public Unavailable() { super("Upstream response unavailable"); } }
    public static String serviceUrl(String url,String service) {
        URI u=URI.create(url); String h=u.getHost();
        boolean local=h!=null&&(h.equals("127.0.0.1")||h.equals("localhost")||h.equals(service)||h.matches(java.util.regex.Pattern.quote(service)+"\\.[a-z0-9-]+\\.svc(?:\\.cluster\\.local)?"));
        if(h==null||!("https".equals(u.getScheme())||("http".equals(u.getScheme())&&local))||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!u.getPath().isEmpty()) throw new IllegalArgumentException("Invalid service endpoint");
        return url;
    }
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
        private final int limit; private java.util.concurrent.Flow.Subscription subscription;
        LimitedSubscriber(int limit) { this.limit=limit; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>limit-body.size()) { subscription.cancel(); result.completeExceptionally(new Unavailable()); return; }
                byte[] bytes=new byte[buffer.remaining()]; buffer.get(bytes); body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(new Unavailable()); }
        public void onComplete() { result.complete(body.toByteArray()); }
    }
}
