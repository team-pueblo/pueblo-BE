package com.pueblo.member.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

@Configuration
@ConditionalOnProperty(name="pueblo.docs.enabled", havingValue="true")
public class SwaggerCsrfConfiguration {
    @Bean
    SwaggerIndexTransformer swaggerIndexTransformer(SwaggerUiConfigProperties properties,
            SwaggerUiOAuthProperties oauth, SwaggerWelcomeCommon welcome, ObjectMapperProvider mapper) {
        return new SwaggerIndexPageTransformer(properties, oauth, welcome, mapper) {
            @Override
            public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
                    throws IOException {
                Resource transformed = super.transform(request, resource, chain);
                if (!"swagger-initializer.js".equals(resource.getFilename())) return transformed;
                String script = transformed.getContentAsString(StandardCharsets.UTF_8);
                String interceptor = new ClassPathResource("swagger-csrf-interceptor.js")
                        .getContentAsString(StandardCharsets.UTF_8);
                String updated = script.replace("SwaggerUIBundle({", "SwaggerUIBundle({\nrequestInterceptor: " + interceptor + ",\n");
                return new TransformedResource(transformed, updated.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
