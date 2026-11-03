package com.erpbanking.transactions.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.dto.AccountCreateRequest;
import com.erpbanking.transactions.exception.ApiExceptions.AccountNotFoundException;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.service.lock.CustomerAccountLock;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerAccountLock customerLock;

    public AccountService(AccountRepository accountRepository, CustomerAccountLock customerLock) {
        this.accountRepository = accountRepository;
        this.customerLock = customerLock;
    }

    public Account create(AccountCreateRequest req) {
        BigDecimal saldo = req.saldoInicial() == null ? BigDecimal.ZERO : req.saldoInicial();
        return accountRepository.save(new Account(req.customerId(), saldo));
    }

    public Account get(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Conta " + id + " não encontrada"));
    }

    /**
     * Abertura automática (evento {@code customer.created}): idempotente. Se o cliente já tem conta,
     * não faz nada e retorna vazio. Antes da verificação trava o cliente ({@link CustomerAccountLock}, na mesma
     * transação), para duas entregas paralelas não abrirem duas contas.
     */
    @Transactional
    public Optional<Account> openForNewCustomer(Long customerId) {
        customerLock.lock(customerId);
        if (!accountRepository.findByCustomerIdOrderByIdDesc(customerId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(accountRepository.save(new Account(customerId, BigDecimal.ZERO)));
    }

    /** Contas mais recentes primeiro; filtra pelo cliente quando informado. */
    public List<Account> list(Long customerId) {
        if (customerId == null) {
            return accountRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        }
        return accountRepository.findByCustomerIdOrderByIdDesc(customerId);
    }
}
