package com.erpbanking.transactions.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AccountCreateRequest(
        @NotNull Long customerId,
        @NotNull @PositiveOrZero BigDecimal saldoInicial
) {}
