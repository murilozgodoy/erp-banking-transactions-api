package com.erpbanking.transactions.service.client;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.erpbanking.transactions.config.CreditScoreProperties;
import com.erpbanking.transactions.config.LogSafe;
import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.exception.ApiExceptions.ScoreForbiddenException;
import com.erpbanking.transactions.exception.ApiExceptions.ScoreUnavailableException;
import com.erpbanking.transactions.exception.ScoreServiceUnavailableException;

/**
 * Client do Credit Score API com resiliência manual (sem Resilience4j):
 * <ul>
 *   <li>Retry com backoff exponencial em falhas transitórias (I/O/timeout, 502/503/504).</li>
 *   <li>Circuit breaker: após N falhas consecutivas abre por um cooldown e falha rápido.</li>
 *   <li>Respostas 4xx não são repetidas nem contam como falha do circuito.</li>
 * </ul>
 * Indisponibilidade (tentativas esgotadas ou circuito aberto) vira
 * {@link ScoreServiceUnavailableException} (HTTP 503).
 */
@Component
public class CreditScoreClient {

    private static final Logger log = LoggerFactory.getLogger(CreditScoreClient.class);

    /** Abstração do sleep do backoff, para manter os testes rápidos. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final ScoreCircuitBreaker circuitBreaker;
    private final Sleeper sleeper;

    @Autowired
    public CreditScoreClient(RestTemplate creditScoreRestTemplate, CreditScoreProperties props) {
        this(creditScoreRestTemplate, props, Clock.systemUTC(), Thread::sleep);
    }

    CreditScoreClient(RestTemplate restTemplate, CreditScoreProperties props, Clock clock, Sleeper sleeper) {
        this.restTemplate = restTemplate;
        this.baseUrl = props.getBaseUrl();
        this.maxRetries = Math.max(0, props.getMaxRetries());
        this.retryBackoffMs = Math.max(0, props.getRetryBackoffMs());
        this.circuitBreaker = new ScoreCircuitBreaker(props.getCircuitFailureThreshold(),
                Duration.ofMillis(props.getCircuitOpenDurationMs()), clock);
        this.sleeper = sleeper;
    }

    public Optional<ScoreResponse> getLatestScore(Long customerId) {
        ScoreCircuitBreaker.Permit permit = circuitBreaker.tryAcquire();
        if (permit == ScoreCircuitBreaker.Permit.REJECTED) {
            log.warn("Circuit breaker do Credit Score aberto; falhando rápido (customerId={})", customerId);
            throw new ScoreServiceUnavailableException();
        }
        // Em half-open: uma única chamada de teste, sem retry.
        int maxAttempts = permit == ScoreCircuitBreaker.Permit.PROBE ? 1 : 1 + maxRetries;
        String url = baseUrl + "/scores/customers/" + customerId + "/latest";

        RestClientException lastError;
        for (int attempt = 1; ; attempt++) {
            try {
                ScoreResponse resp = restTemplate.getForObject(url, ScoreResponse.class);
                circuitBreaker.onSuccess();
                return Optional.ofNullable(resp);
            } catch (HttpClientErrorException e) {
                // 4xx: o serviço respondeu; não repete nem conta como falha.
                circuitBreaker.onSuccess();
                return handleClientError(e);
            } catch (HttpServerErrorException e) {
                if (!isTransient(e.getStatusCode())) {
                    circuitBreaker.onFailure();
                    throw new ScoreUnavailableException("Score API respondeu " + e.getStatusCode());
                }
                lastError = e;
            } catch (ResourceAccessException e) {
                lastError = e;
            } catch (RestClientException e) {
                // Resposta recebida, mas inválida (ex.: corpo não desserializável): não é transitória.
                circuitBreaker.onSuccess();
                throw new ScoreUnavailableException("Falha ao contactar Score API: " + LogSafe.describe(e));
            }

            if (attempt >= maxAttempts) {
                circuitBreaker.onFailure();
                log.warn("Credit Score indisponível após {} tentativa(s): {}", attempt, LogSafe.describe(lastError));
                throw new ScoreServiceUnavailableException(lastError);
            }
            long backoff = retryBackoffMs << (attempt - 1);
            log.debug("Falha transitória no Credit Score (tentativa {}/{}); nova tentativa em {} ms",
                    attempt, maxAttempts, backoff);
            try {
                sleeper.sleep(backoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                circuitBreaker.onFailure();
                throw new ScoreServiceUnavailableException(lastError);
            }
        }
    }

    ScoreCircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    private static boolean isTransient(HttpStatusCode status) {
        int code = status.value();
        return code == 502 || code == 503 || code == 504;
    }

    private static Optional<ScoreResponse> handleClientError(HttpClientErrorException e) {
        if (e.getStatusCode().equals(HttpStatusCode.valueOf(404))) {
            return Optional.empty();
        }
        if (e.getStatusCode().equals(HttpStatusCode.valueOf(403))) {
            throw new ScoreForbiddenException("Sem permissão para consultar o score (read:scores)");
        }
        throw new ScoreUnavailableException("Score API respondeu " + e.getStatusCode());
    }
}
