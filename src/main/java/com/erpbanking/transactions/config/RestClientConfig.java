package com.erpbanking.transactions.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configura o RestTemplate como Singleton (padrão Spring @Bean).
 * Reutilizado por todos os clients HTTP internos do serviço; repassa o token do usuário.
 * Connect/read timeouts separados para o Credit Score (defaults: 2s / 5s).
 */
@Configuration
@EnableConfigurationProperties(CreditScoreProperties.class)
public class RestClientConfig {

    @Bean
    public RestTemplate creditScoreRestTemplate(RestTemplateBuilder builder,
                                                CreditScoreProperties props) {
        return builder
                .setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .additionalInterceptors(new BearerTokenRelayInterceptor())
                .build();
    }
}
