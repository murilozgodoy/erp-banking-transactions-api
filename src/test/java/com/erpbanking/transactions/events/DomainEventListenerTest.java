package com.erpbanking.transactions.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.metrics.BusinessMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DomainEventListenerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DomainEventListener listener = new DomainEventListener(new BusinessMetrics(registry));

    @Test
    void creditDecisionIncrementsCounterByStatus() {
        listener.onCreditDecided(new CreditDecidedEvent(1L, 2L, BigDecimal.TEN, 12, 800.0, "BAIXO",
                CreditStatus.APROVADO, "ok", new BigDecimal("0.0299")));

        assertThat(registry.get(BusinessMetrics.CREDIT_DECISIONS).tag("status", "APROVADO").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(BusinessMetrics.CREDIT_DECISIONS).tag("status", "NEGADO").counter().count())
                .isZero();
    }

    @Test
    void transactionIncrementsCounterByTipo() {
        listener.onTransactionCreated(new TransactionCreatedEvent(1L, 2L, 3L, TransactionType.DEBIT,
                BigDecimal.ONE, BigDecimal.TEN));

        assertThat(registry.get(BusinessMetrics.TRANSACTIONS_CREATED).tag("tipo", "DEBIT").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void eventPublishedCountsByRoutingKeyAndResult() {
        BusinessMetrics metrics = new BusinessMetrics(registry);
        metrics.eventPublished("credit.decided", "ok");
        metrics.eventPublished("credit.decided", "ok");

        assertThat(registry.get(BusinessMetrics.EVENTS_PUBLISHED)
                .tags("routing_key", "credit.decided", "result", "ok").counter().count()).isEqualTo(2.0);
    }
}
