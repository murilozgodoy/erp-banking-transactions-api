package com.erpbanking.transactions.service.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import com.erpbanking.transactions.config.CreditScoreProperties;
import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.exception.ApiExceptions.ScoreForbiddenException;
import com.erpbanking.transactions.exception.ApiExceptions.ScoreUnavailableException;
import com.erpbanking.transactions.exception.ScoreServiceUnavailableException;

class CreditScoreClientResilienceTest {

    private static final String URL = "http://score.test/scores/customers/1/latest";
    private static final String BODY = "{\"customer_id\":1,\"score\":800,\"faixa_risco\":\"BAIXO\"}";

    private MockRestServiceServer server;
    private MutableClock clock;
    private List<Long> sleeps;
    private CreditScoreClient client;
    private RestTemplate restTemplate;
    private CreditScoreProperties props;

    @BeforeEach
    void setup() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        props = new CreditScoreProperties();
        props.setBaseUrl("http://score.test");
        props.setMaxRetries(2);
        props.setRetryBackoffMs(10);
        props.setCircuitFailureThreshold(3);
        props.setCircuitOpenDurationMs(30_000);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        sleeps = new ArrayList<>();
        client = new CreditScoreClient(restTemplate, props, clock, sleeps::add);
    }

    private static ResponseCreator ioError() {
        return request -> {
            throw new SocketTimeoutException("Read timed out");
        };
    }

    @Test
    void retriesTransientErrorsThenSucceeds() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(URL)).andRespond(ioError());
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        Optional<ScoreResponse> result = client.getLatestScore(1L);

        assertThat(result).isPresent();
        assertThat(sleeps).containsExactly(10L, 20L);
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test
    void retriesExhaustedThrowsServiceUnavailable() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> client.getLatestScore(1L))
                .isInstanceOf(ScoreServiceUnavailableException.class)
                .hasMessage(ScoreServiceUnavailableException.MESSAGE);
        server.verify();
    }

    @Test
    void retriesBadGateway() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client.getLatestScore(1L)).isPresent();
        server.verify();
    }

    @Test
    void doesNotRetry4xxNorCountThemAsFailures() {
        server.expect(times(5), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreUnavailableException.class);
        }
        assertThat(sleeps).isEmpty();
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test
    void forbiddenAndNotFoundAreNotRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreForbiddenException.class);
        assertThat(client.getLatestScore(1L)).isEmpty();
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void internalServerErrorIsNotRetriedButCountsForBreaker() {
        server.expect(times(3), requestTo(URL)).andRespond(withServerError());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreUnavailableException.class);
        }
        assertThat(sleeps).isEmpty();
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.OPEN);
        server.verify();
    }

    @Test
    void circuitOpensAfterConsecutiveFailuresAndFailsFast() {
        props.setMaxRetries(0);
        client = new CreditScoreClient(restTemplate, props, clock, sleeps::add);
        server.expect(times(3), requestTo(URL)).andRespond(ioError());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.getLatestScore(1L))
                    .isInstanceOf(ScoreServiceUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.OPEN);

        // Circuito aberto: falha rápido sem chamar o Score (o server não espera mais requisições).
        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(() -> client.getLatestScore(1L))
                .isInstanceOf(ScoreServiceUnavailableException.class)
                .hasMessage(ScoreServiceUnavailableException.MESSAGE);
        server.verify();
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        props.setMaxRetries(0);
        client = new CreditScoreClient(restTemplate, props, clock, sleeps::add);
        server.expect(times(2), requestTo(URL)).andRespond(ioError());
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        server.expect(times(2), requestTo(URL)).andRespond(ioError());

        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreServiceUnavailableException.class);
        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreServiceUnavailableException.class);
        assertThat(client.getLatestScore(1L)).isPresent();
        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreServiceUnavailableException.class);
        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreServiceUnavailableException.class);
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.CLOSED);
        server.verify();
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() {
        openCircuit();
        clock.advance(Duration.ofSeconds(30));
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client.getLatestScore(1L)).isPresent();
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.CLOSED);
        assertThat(client.getLatestScore(1L)).isPresent();
        server.verify();
    }

    @Test
    void halfOpenProbeFailureReopensCircuitWithoutRetry() {
        openCircuit();
        clock.advance(Duration.ofSeconds(31));
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreServiceUnavailableException.class);
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.OPEN);
        // Reaberto: falha rápido de novo, sem nova requisição.
        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void interruptedBackoffFailsAsUnavailable() {
        client = new CreditScoreClient(restTemplate, props, clock, ms -> {
            throw new InterruptedException();
        });
        server.expect(once(), requestTo(URL)).andRespond(ioError());

        try {
            assertThatThrownBy(() -> client.getLatestScore(1L))
                    .isInstanceOf(ScoreServiceUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }

    @Test
    void invalidBodyIsNotRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreUnavailableException.class);
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    private void openCircuit() {
        props.setMaxRetries(0);
        client = new CreditScoreClient(restTemplate, props, clock, sleeps::add);
        server.expect(times(3), requestTo(URL)).andRespond(ioError());
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.getLatestScore(1L)).isInstanceOf(ScoreServiceUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(ScoreCircuitBreaker.State.OPEN);
        server.verify();
        server.reset();
    }

    /** Clock controlável para testar o cooldown do circuit breaker sem esperar. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
