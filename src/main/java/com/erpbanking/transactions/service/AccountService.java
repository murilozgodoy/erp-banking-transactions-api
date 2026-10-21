package com.erpbanking.transactions.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Sort;
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

    /** Contas mais recentes primeiro; filtra pelo cliente quando informado. */
    public List<Account> list(Long customerId) {
        if (customerId == null) {
            return accountRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        }
        return accountRepository.findByCustomerIdOrderByIdDesc(customerId);
    }
}
