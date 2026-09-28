package com.erpbanking.transactions.dto;

import java.math.BigDecimal;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.AccountStatus;

public record AccountResponse(Long id, Long customerId, BigDecimal saldo, AccountStatus status) {

    public static AccountResponse of(Account a) {
        return new AccountResponse(a.getId(), a.getCustomerId(), a.getSaldo(), a.getStatus());
    }
}
