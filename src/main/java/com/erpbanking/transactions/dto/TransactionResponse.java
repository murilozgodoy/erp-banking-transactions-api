package com.erpbanking.transactions.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.erpbanking.transactions.domain.Transaction;
import com.erpbanking.transactions.domain.TransactionType;

public record TransactionResponse(
        Long id, Long accountId, TransactionType tipo, BigDecimal valor,
        BigDecimal saldoAtual, Instant timestamp
) {
    public static TransactionResponse of(Transaction t, BigDecimal saldoAtual) {
        return new TransactionResponse(t.getId(), t.getAccountId(), t.getTipo(),
                t.getValor(), saldoAtual, t.getTimestamp());
    }
}
