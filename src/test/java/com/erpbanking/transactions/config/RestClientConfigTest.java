package com.erpbanking.transactions.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

class RestClientConfigTest {

    @Test
    void defaultsAreSane() {
        CreditScoreProperties props = new CreditScoreProperties();

        assertThat(props.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(props.getReadTimeoutMs()).isEqualTo(5000);
        assertThat(props.getMaxRetries()).isEqualTo(2);
        assertThat(props.getRetryBackoffMs()).isEqualTo(200);
        assertThat(props.getCircuitFailureThreshold()).isEqualTo(5);
        assertThat(props.getCircuitOpenDurationMs()).isEqualTo(30_000);
    }

    @Test
    void readTimeoutFallsBackToLegacyTimeoutUnlessSetExplicitly() {
        CreditScoreProperties props = new CreditScoreProperties();
        props.setTimeoutMs(1000);
        assertThat(props.getReadTimeoutMs()).isEqualTo(1000);

        props.setReadTimeoutMs(3000);
        assertThat(props.getReadTimeoutMs()).isEqualTo(3000);
    }

    @Test
    void buildsRestTemplateWithTokenRelayInterceptor() {
        CreditScoreProperties props = new CreditScoreProperties();
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(700);

        RestTemplate template = new RestClientConfig().creditScoreRestTemplate(new RestTemplateBuilder(), props);

        assertThat(template.getInterceptors())
                .hasSize(1)
                .first()
                .isInstanceOf(BearerTokenRelayInterceptor.class);
    }
}
