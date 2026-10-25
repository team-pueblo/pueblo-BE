package com.pueblo.order.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import com.pueblo.common.exception.ErrorCode;
import com.pueblo.order.exception.OrderErrorCode;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Calls service APIs; never connects to another service's database. No redirects or token caching. */
public final class ServiceClients {
    private final RestClient member;
    private final RestClient catalog;
    private final ObjectMapper mapper;
    public ServiceClients(String memberUrl, String catalogUrl, ObjectMapper mapper) {
        this.mapper = mapper;
        member = client(memberUrl, "member-service");
        catalog = client(catalogUrl, "catalog-service");
    }
    private RestClient client(String value, String service) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean internal = host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals(service) || host.matches(java.util.regex.Pattern.quote(service) + "\\.[a-z0-9-]+\\.svc(?:\\.cluster\\.local)?"));
        if (host == null || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && internal))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Invalid service endpoint");
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        return RestClient.builder().baseUrl(value).requestFactory(factory).build();
    }
    public UUID member(String authorization) {
        JsonNode data = read(member, "/api/v1/me", authorization, 16384, true);
        try { return UUID.fromString(data.path("id").asText()); }
        catch (RuntimeException ex) { throw new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE); }
    }
    public JsonNode product(UUID productId) {
        return read(catalog, "/api/v1/products/" + productId, null, 262144, false);
    }
    private JsonNode read(RestClient client, String path, String auth, int limit, boolean identity) {
        ErrorCode unavailable = identity ? CommonErrorCode.AUTH_STORE_UNAVAILABLE : CommonErrorCode.DEPENDENCY_UNAVAILABLE;
        try {
            var request = client.get().uri(path);
            if (auth != null) request.header("Authorization", auth);
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (identity && (status == 401 || status == 403)) throw new BusinessException(CommonErrorCode.INVALID_CREDENTIALS);
                if (!identity && status == 404) throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE);
                if (status != 200) throw new BusinessException(unavailable);
                byte[] body = response.getBody().readNBytes(limit + 1);
                if (body.length > limit) throw new BusinessException(unavailable);
                JsonNode data = mapper.readTree(body).path("data");
                if (!data.isObject()) throw new BusinessException(unavailable);
                return data;
            });
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException(unavailable); }
    }
}
