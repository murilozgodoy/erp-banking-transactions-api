package com.erpbanking.transactions.messaging;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.service.AccountService;

/**
 * Consome {@code customer.created} (Onboarding) na fila {@code transactions.customer-created} e abre a conta
 * corrente do cliente com saldo zero. Idempotente: a mesma mensagem reentregue não abre uma segunda conta.
 * Payload inválido é descartado sem requeue, para não entrar em loop.
 */
public class CustomerCreatedListener {

    public static final String QUEUE = "transactions.customer-created";
    public static final String ROUTING_KEY = "customer.created";

    private static final Logger log = LoggerFactory.getLogger(CustomerCreatedListener.class);

    private final AccountService accountService;
    private final BusinessMetrics metrics;

    public CustomerCreatedListener(AccountService accountService, BusinessMetrics metrics) {
        this.accountService = accountService;
        this.metrics = metrics;
        metrics.initConsumed(ROUTING_KEY, "ok", "duplicate", "invalid");
    }

    @RabbitListener(queues = QUEUE)
    public void onCustomerCreated(Map<String, Object> event) {
        Long customerId = customerId(event);
        if (customerId == null) {
            log.warn("customer.created sem customer_id valido, descartado: {}", event);
            metrics.eventConsumed(ROUTING_KEY, "invalid");
            throw new AmqpRejectAndDontRequeueException("customer_id ausente ou invalido");
        }
        accountService.openForNewCustomer(customerId).ifPresentOrElse(
                account -> {
                    log.info("Conta {} aberta automaticamente para o cliente {}", account.getId(), customerId);
                    metrics.eventConsumed(ROUTING_KEY, "ok");
                },
                () -> {
                    log.info("Cliente {} ja possui conta; customer.created ignorado", customerId);
                    metrics.eventConsumed(ROUTING_KEY, "duplicate");
                });
    }

    static Long customerId(Map<String, Object> event) {
        Object raw = event == null ? null : event.get("customer_id");
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
