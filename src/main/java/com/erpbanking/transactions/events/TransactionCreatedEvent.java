package com.erpbanking.transactions.events;

import java.math.BigDecimal;

import com.erpbanking.transactions.domain.TransactionType;

/** Evento de domínio: uma transação foi registrada (publicado como {@code transaction.created}). */
public record TransactionCreatedEvent(
        Long transactionId, Long accountId, Long customerId, TransactionType tipo,
        BigDecimal valor, BigDecimal saldoApos
) {
    public static final String ROUTING_KEY = "transaction.created";
}
