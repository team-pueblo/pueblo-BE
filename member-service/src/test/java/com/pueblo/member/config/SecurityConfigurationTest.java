package com.pueblo.member.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigurationTest.PrivateController.class)
@Import({SecurityConfiguration.class, SecurityConfigurationTest.PrivateController.class})
class SecurityConfigurationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    void businessRoutesRemainClosedUntilAuthenticationIsImplemented() throws Exception {
        mvc.perform(get("/api/v1/private")).andExpect(status().isUnauthorized());
    }

    @RestController
    static class PrivateController {
        @GetMapping("/api/v1/private")
        String privateEndpoint() {
            return "must not be exposed";
        }
    }
}
