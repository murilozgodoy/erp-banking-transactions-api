package com.erpbanking.transactions.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.dto.AccountCreateRequest;
import com.erpbanking.transactions.exception.ApiExceptions.AccountNotFoundException;
import com.erpbanking.transactions.repository.AccountRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account create(AccountCreateRequest req) {
        BigDecimal saldo = req.saldoInicial() == null ? BigDecimal.ZERO : req.saldoInicial();
        return accountRepository.save(new Account(req.customerId(), saldo));
    }

    public Account get(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Conta " + id + " não encontrada"));
    }
}
