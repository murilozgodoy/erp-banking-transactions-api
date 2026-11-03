package com.erpbanking.transactions.events;

import java.math.BigDecimal;

import com.erpbanking.transactions.domain.CreditStatus;

/** Evento de domínio: um pedido de crédito foi decidido (publicado como {@code credit.decided}). */
public record CreditDecidedEvent(
        Long creditRequestId, Long customerId, BigDecimal valorSolicitado, int prazoMeses,
        Double score, String faixaRisco, CreditStatus status, String motivo, BigDecimal taxaJuros
) {
    public static final String ROUTING_KEY = "credit.decided";
}
