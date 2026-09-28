package com.erpbanking.transactions.dto;

import java.math.BigDecimal;

import com.erpbanking.transactions.domain.TransactionType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransactionRequest(
        @NotNull Long accountId,
        @NotNull TransactionType tipo,
        @NotNull @Positive BigDecimal valor
) {}
