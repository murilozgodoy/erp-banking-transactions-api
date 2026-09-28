package com.erpbanking.transactions.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreditRequestDto(
        @NotNull Long customerId,
        @NotNull @Positive BigDecimal valor,
        @NotNull @Min(1) Integer prazoMeses
) {}
