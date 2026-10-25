package com.pueblo.api;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class OpenApiDisabledTest {
    @Test void documentationIsNotEnabledByDefault() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenApiAutoConfiguration.class))
                .run(context->{
                    assertThat(context).doesNotHaveBean("documentationSecurity");
                    assertThat(context).doesNotHaveBean("serviceOpenApi");
                });
    }
}
