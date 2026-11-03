package com.erpbanking.transactions.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da chamada ao Credit Score API (timeouts, retry e circuit breaker).
 * Todos os campos têm defaults em código; o application.yml pode sobrescrevê-los.
 */
@ConfigurationProperties(prefix = "credit-score")
public class CreditScoreProperties {

    private String baseUrl = "http://localhost:8082";

    /** Legado: usado como read timeout quando {@code readTimeoutMs} não é informado. */
    private int timeoutMs = 5000;

    /** Timeout de conexão (ms). */
    private int connectTimeoutMs = 2000;

    /** Timeout de leitura (ms); se nulo, usa {@code timeoutMs}. */
    private Integer readTimeoutMs;

    /** Número de novas tentativas em falhas transitórias (I/O, timeout, 502/503/504). */
    private int maxRetries = 2;

    /** Backoff base entre tentativas (ms); cresce exponencialmente a cada tentativa. */
    private long retryBackoffMs = 200;

    /** Falhas consecutivas que abrem o circuito. */
    private int circuitFailureThreshold = 5;

    /** Tempo (ms) que o circuito permanece aberto antes de ir para half-open. */
    private long circuitOpenDurationMs = 30_000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs != null ? readTimeoutMs : timeoutMs;
    }

    public void setReadTimeoutMs(Integer readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public long getCircuitOpenDurationMs() {
        return circuitOpenDurationMs;
    }

    public void setCircuitOpenDurationMs(long circuitOpenDurationMs) {
        this.circuitOpenDurationMs = circuitOpenDurationMs;
    }
}
