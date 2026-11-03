package com.erpbanking.transactions.events;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.erpbanking.transactions.messaging.EventPublisher;
import com.erpbanking.transactions.metrics.BusinessMetrics;

/**
 * Reage aos eventos de domínio somente depois do commit (o que foi revertido não conta nem é publicado):
 * atualiza as métricas de negócio e publica o evento de integração no RabbitMQ.
 */
@Component
public class DomainEventListener {

    private final BusinessMetrics metrics;
    private final EventPublisher publisher;

    public DomainEventListener(BusinessMetrics metrics, EventPublisher publisher) {
        this.metrics = metrics;
        this.publisher = publisher;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onCreditDecided(CreditDecidedEvent event) {
        metrics.creditDecided(event.status());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("credit_request_id", event.creditRequestId());
        payload.put("customer_id", event.customerId());
        payload.put("valor_solicitado", event.valorSolicitado());
        payload.put("prazo_meses", event.prazoMeses());
        payload.put("score", event.score());
        payload.put("faixa_risco", event.faixaRisco());
        payload.put("status", event.status().name());
        payload.put("motivo", event.motivo());
        payload.put("taxa_juros", event.taxaJuros());
        publisher.publish(CreditDecidedEvent.ROUTING_KEY, payload);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onTransactionCreated(TransactionCreatedEvent event) {
        metrics.transactionCreated(event.tipo());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transaction_id", event.transactionId());
        payload.put("account_id", event.accountId());
        payload.put("customer_id", event.customerId());
        payload.put("tipo", event.tipo().name());
        payload.put("valor", event.valor());
        payload.put("saldo_apos", event.saldoApos());
        publisher.publish(TransactionCreatedEvent.ROUTING_KEY, payload);
    }
}
