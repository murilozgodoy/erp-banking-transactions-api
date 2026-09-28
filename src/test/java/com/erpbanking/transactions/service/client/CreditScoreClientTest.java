package com.erpbanking.transactions.service.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.erpbanking.transactions.config.CreditScoreProperties;
import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.exception.ApiExceptions.ScoreUnavailableException;

class CreditScoreClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private CreditScoreClient client;

    @BeforeEach
    void setup() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        CreditScoreProperties props = new CreditScoreProperties();
        props.setBaseUrl("http://score.test");
        client = new CreditScoreClient(restTemplate, props);
    }

    @Test
    void returnsScoreWhenFound() {
        server.expect(requestTo("http://score.test/scores/customers/1/latest"))
                .andRespond(withSuccess(
                        "{\"customer_id\":1,\"score\":800,\"faixa_risco\":\"BAIXO\"}",
                        MediaType.APPLICATION_JSON));

        Optional<ScoreResponse> result = client.getLatestScore(1L);
        assertThat(result).isPresent();
        assertThat(result.get().faixaRisco()).isEqualTo("BAIXO");
    }

    @Test
    void returnsEmptyWhen404() {
        server.expect(requestTo("http://score.test/scores/customers/42/latest"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<ScoreResponse> result = client.getLatestScore(42L);
        assertThat(result).isEmpty();
    }

    @Test
    void throwsWhen500() {
        server.expect(requestTo("http://score.test/scores/customers/1/latest"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getLatestScore(1L))
                .isInstanceOf(ScoreUnavailableException.class);
    }

    @Test
    void throwsOnOther4xx() {
        server.expect(requestTo("http://score.test/scores/customers/1/latest"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.getLatestScore(1L))
                .isInstanceOf(ScoreUnavailableException.class);
    }
}
