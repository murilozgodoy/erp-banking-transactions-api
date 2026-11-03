package com.erpbanking.transactions.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.messaging.EventPublisher;
import com.erpbanking.transactions.metrics.BusinessMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DomainEventListenerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final DomainEventListener listener = new DomainEventListener(new BusinessMetrics(registry), publisher);

    @SuppressWarnings("unchecked")
    private Map<String, Object> published(String routingKey) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(routingKey), captor.capture());
        return captor.getValue();
    }

    @Test
    void creditDecisionIncrementsCounterAndPublishesCreditDecided() {
        listener.onCreditDecided(new CreditDecidedEvent(1L, 2L, BigDecimal.TEN, 12, 800.0, "BAIXO",
                CreditStatus.APROVADO, "ok", new BigDecimal("0.0299")));

        assertThat(registry.get(BusinessMetrics.CREDIT_DECISIONS).tag("status", "APROVADO").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(BusinessMetrics.CREDIT_DECISIONS).tag("status", "NEGADO").counter().count())
                .isZero();
        assertThat(published("credit.decided"))
                .containsEntry("credit_request_id", 1L)
                .containsEntry("customer_id", 2L)
                .containsEntry("valor_solicitado", BigDecimal.TEN)
                .containsEntry("prazo_meses", 12)
                .containsEntry("score", 800.0)
                .containsEntry("faixa_risco", "BAIXO")
                .containsEntry("status", "APROVADO")
                .containsEntry("motivo", "ok")
                .containsEntry("taxa_juros", new BigDecimal("0.0299"));
    }

    @Test
    void creditDecisionWithoutScoreKeepsNullFields() {
        listener.onCreditDecided(new CreditDecidedEvent(1L, 2L, BigDecimal.TEN, 12, null, null,
                CreditStatus.NEGADO, "sem score", BigDecimal.ZERO));

        assertThat(published("credit.decided")).containsEntry("score", null).containsEntry("faixa_risco", null);
    }

    @Test
    void transactionIncrementsCounterAndPublishesTransactionCreated() {
        listener.onTransactionCreated(new TransactionCreatedEvent(1L, 2L, 3L, TransactionType.DEBIT,
                BigDecimal.ONE, BigDecimal.TEN));

        assertThat(registry.get(BusinessMetrics.TRANSACTIONS_CREATED).tag("tipo", "DEBIT").counter().count())
                .isEqualTo(1.0);
        assertThat(published("transaction.created"))
                .containsEntry("transaction_id", 1L)
                .containsEntry("account_id", 2L)
                .containsEntry("customer_id", 3L)
                .containsEntry("tipo", "DEBIT")
                .containsEntry("valor", BigDecimal.ONE)
                .containsEntry("saldo_apos", BigDecimal.TEN);
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
