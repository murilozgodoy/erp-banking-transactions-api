package com.erpbanking.transactions.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configura o RestTemplate como Singleton (padrão Spring @Bean).
 * Reutilizado por todos os clients HTTP internos do serviço.
 */
@Configuration
@EnableConfigurationProperties(CreditScoreProperties.class)
public class RestClientConfig {

    @Bean
    public RestTemplate creditScoreRestTemplate(RestTemplateBuilder builder,
                                                CreditScoreProperties props) {
        return builder
                .setConnectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }
}
