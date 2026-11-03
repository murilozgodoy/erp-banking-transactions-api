package com.erpbanking.transactions.metrics;

import org.springframework.stereotype.Component;

import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.TransactionType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Métricas de negócio expostas em /actuator/prometheus. Os nomes são contrato com o Grafana:
 * {@code credit_decisions_total{status}}, {@code transactions_created_total{tipo}} e
 * {@code events_published_total{routing_key,result}}.
 */
@Component
public class BusinessMetrics {

    public static final String CREDIT_DECISIONS = "credit.decisions";
    public static final String TRANSACTIONS_CREATED = "transactions.created";
    public static final String EVENTS_PUBLISHED = "events.published";
    public static final String EVENTS_CONSUMED = "events.consumed";

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Registra as séries com zero para os painéis existirem desde o startup.
        for (CreditStatus status : CreditStatus.values()) {
            if (status.decisao()) {
                creditDecisions(status);
            }
        }
        for (TransactionType tipo : TransactionType.values()) {
            transactionsCreated(tipo);
        }
    }

    public void creditDecided(CreditStatus status) {
        creditDecisions(status).increment();
    }

    public void transactionCreated(TransactionType tipo) {
        transactionsCreated(tipo).increment();
    }

    public void eventPublished(String routingKey, String result) {
        published(routingKey, result).increment();
    }

    public void eventConsumed(String routingKey, String result) {
        consumed(routingKey, result).increment();
    }

    /** Cria as séries {@code events_published_total} com zero (dashboards mostram 0 em vez de "No data"). */
    public void initPublished(String... routingKeys) {
        for (String routingKey : routingKeys) {
            for (String result : new String[]{"ok", "error"}) {
                published(routingKey, result);
            }
        }
    }

    /** Cria as séries {@code events_consumed_total} com zero. */
    public void initConsumed(String routingKey, String... results) {
        for (String result : results) {
            consumed(routingKey, result);
        }
    }

    private Counter published(String routingKey, String result) {
        return Counter.builder(EVENTS_PUBLISHED)
                .description("Eventos publicados no RabbitMQ (exchange erp.events)")
                .tag("routing_key", routingKey)
                .tag("result", result)
                .register(registry);
    }

    private Counter consumed(String routingKey, String result) {
        return Counter.builder(EVENTS_CONSUMED)
                .description("Eventos consumidos do RabbitMQ")
                .tag("routing_key", routingKey)
                .tag("result", result)
                .register(registry);
    }

    private Counter creditDecisions(CreditStatus status) {
        return Counter.builder(CREDIT_DECISIONS)
                .description("Decisões de crédito por status")
                .tag("status", status.name())
                .register(registry);
    }

    private Counter transactionsCreated(TransactionType tipo) {
        return Counter.builder(TRANSACTIONS_CREATED)
                .description("Transações registradas por tipo")
                .tag("tipo", tipo.name())
                .register(registry);
    }
}
