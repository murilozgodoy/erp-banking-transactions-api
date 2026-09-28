package com.erpbanking.transactions.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditStatus;

public record CreditResponseDto(
        Long id, Long customerId, BigDecimal valorSolicitado, int prazoMeses,
        Double scoreSnapshot, CreditStatus status, String motivo,
        BigDecimal valorAprovado, BigDecimal taxaJuros, Instant criadoEm
) {
    public static CreditResponseDto of(CreditRequest cr, BigDecimal valorAprovado, BigDecimal taxaJuros) {
        return new CreditResponseDto(
                cr.getId(), cr.getCustomerId(), cr.getValorSolicitado(),
                cr.getPrazoMeses(), cr.getScoreSnapshot(), cr.getStatus(),
                cr.getMotivo(), valorAprovado, taxaJuros, cr.getCriadoEm()
        );
    }
}
