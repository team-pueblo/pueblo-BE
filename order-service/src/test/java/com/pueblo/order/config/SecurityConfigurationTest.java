package com.pueblo.order.config;

import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigurationTest.PrivateController.class)
@Import({SecurityConfiguration.class, SecurityConfigurationTest.PrivateController.class})
class SecurityConfigurationTest {
    @Autowired
    MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.pueblo.order.checkout.ServiceClients clients;

    @Test
    void businessRoutesRemainClosedUntilAuthenticationIsImplemented() throws Exception {
        mvc.perform(get("/api/v1/private")).andExpect(status().isUnauthorized());
    }

    @Test void bearerPostWorksWithoutCsrfButCookiesDoNotAuthenticate() throws Exception {
        org.mockito.Mockito.when(clients.member("Bearer fixture")).thenReturn(java.util.UUID.randomUUID());
        mvc.perform(post("/api/v1/checkout/quotes").header("Authorization","Bearer fixture")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/checkout/quotes").cookie(new jakarta.servlet.http.Cookie("refresh","fixture"))).andExpect(status().isUnauthorized());
    }
    @Test void memberOutageFailsClosedWithLocalizedError() throws Exception {
        org.mockito.Mockito.when(clients.member("Bearer fixture")).thenThrow(new BusinessException(CommonErrorCode.AUTH_STORE_UNAVAILABLE));
        mvc.perform(post("/api/v1/checkout/quotes").header("Authorization","Bearer fixture").header("Accept-Language","en"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("AUTH_STORE_UNAVAILABLE"));
    }
    @Test void corsAllowsOnlyConfiguredOriginsAndHeaders() throws Exception {
        mvc.perform(options("/api/v1/orders").header("Origin","http://localhost:5173").header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","authorization,idempotency-key"))
                .andExpect(status().isOk());
        mvc.perform(options("/api/v1/orders").header("Origin","https://untrusted.example").header("Access-Control-Request-Method","POST"))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class PrivateController {
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/checkout/quotes")
        String checkout() { return "authenticated"; }
        @GetMapping("/api/v1/private")
        String privateEndpoint() {
            return "must not be exposed";
        }
    }
}
