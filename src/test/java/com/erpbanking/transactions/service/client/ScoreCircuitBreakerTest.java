package com.erpbanking.transactions.service.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ScoreCircuitBreakerTest {

    @Test
    void onlyOneProbeIsAllowedWhileHalfOpen() {
        Clock past = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ScoreCircuitBreaker breaker = new ScoreCircuitBreaker(1, Duration.ZERO, past);

        assertThat(breaker.tryAcquire()).isEqualTo(ScoreCircuitBreaker.Permit.ALLOWED);
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(ScoreCircuitBreaker.State.OPEN);

        // Cooldown zero: vai direto para half-open; só a primeira chamada vira probe.
        assertThat(breaker.tryAcquire()).isEqualTo(ScoreCircuitBreaker.Permit.PROBE);
        assertThat(breaker.state()).isEqualTo(ScoreCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isEqualTo(ScoreCircuitBreaker.Permit.REJECTED);

        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(ScoreCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isEqualTo(ScoreCircuitBreaker.Permit.ALLOWED);
    }

    @Test
    void thresholdBelowOneIsTreatedAsOne() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ScoreCircuitBreaker breaker = new ScoreCircuitBreaker(0, Duration.ofSeconds(30), clock);

        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(ScoreCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isEqualTo(ScoreCircuitBreaker.Permit.REJECTED);
    }
}
