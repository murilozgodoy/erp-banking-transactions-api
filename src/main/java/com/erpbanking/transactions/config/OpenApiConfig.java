package com.erpbanking.transactions.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * OpenAPI/Swagger (springdoc) em {@code /swagger-ui/index.html} e {@code /v3/api-docs}.
 * A documentação é pública; os endpoints continuam exigindo o Bearer token do Auth0 (botão "Authorize").
 */
@Configuration
public class OpenApiConfig {

    public static final String[] PUBLIC_PATHS = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"};
    static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI transactionsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ERP Banking - Transactions API")
                        .version("1.0.0")
                        .description("Contas, transações e solicitações de crédito"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * Aplica X-Forwarded-Prefix/Host/Proto do API Gateway (nginx remove o prefixo /transactions),
     * para o Swagger gerar URLs corretas atrás do gateway.
     */
    @Bean
    FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
