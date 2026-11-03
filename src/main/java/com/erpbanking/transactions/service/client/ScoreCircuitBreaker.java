package com.erpbanking.transactions.service.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Circuit breaker simples (sem dependencias) para a chamada ao Credit Score.
 * <ul>
 *   <li>CLOSED: chamadas passam; apos {@code failureThreshold} falhas consecutivas abre.</li>
 *   <li>OPEN: falha rapido ate {@code openDuration} expirar.</li>
 *   <li>HALF_OPEN: libera uma unica chamada de teste; sucesso fecha, falha reabre.</li>
 * </ul>
 * Thread-safe: o client e um singleton compartilhado entre requisicoes.
 */
final class ScoreCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    enum Permit { ALLOWED, PROBE, REJECTED }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    ScoreCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration;
        this.clock = clock;
    }

    synchronized Permit tryAcquire() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            state = State.HALF_OPEN;
            probeInFlight = false;
        }
        if (state == State.CLOSED) {
            return Permit.ALLOWED;
        }
        if (state == State.HALF_OPEN && !probeInFlight) {
            probeInFlight = true;
            return Permit.PROBE;
        }
        return Permit.REJECTED;
    }

    synchronized void onSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    synchronized State state() {
        return state;
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        probeInFlight = false;
        consecutiveFailures = 0;
    }
}
