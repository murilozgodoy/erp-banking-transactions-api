package com.erpbanking.transactions.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.erpbanking.transactions.metrics.BusinessMetrics;

/**
 * Reage aos eventos de domínio somente depois do commit (o que foi revertido não conta nem é publicado).
 * Atualiza as métricas de negócio.
 */
@Component
public class DomainEventListener {

    private final BusinessMetrics metrics;

    public DomainEventListener(BusinessMetrics metrics) {
        this.metrics = metrics;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onCreditDecided(CreditDecidedEvent event) {
        metrics.creditDecided(event.status());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onTransactionCreated(TransactionCreatedEvent event) {
        metrics.transactionCreated(event.tipo());
    }
}
