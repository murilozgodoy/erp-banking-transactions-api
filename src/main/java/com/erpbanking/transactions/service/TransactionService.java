package com.erpbanking.transactions.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.AccountStatus;
import com.erpbanking.transactions.domain.Transaction;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.dto.TransactionRequest;
import com.erpbanking.transactions.exception.ApiExceptions.AccountBlockedException;
import com.erpbanking.transactions.exception.ApiExceptions.AccountNotFoundException;
import com.erpbanking.transactions.exception.ApiExceptions.InsufficientFundsException;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.TransactionRepository;

@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransactionResult register(TransactionRequest req) {
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException("Conta " + req.accountId() + " não encontrada"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountBlockedException("Conta não está ativa: " + account.getStatus());
        }

        BigDecimal novoSaldo;
        if (req.tipo() == TransactionType.CREDIT) {
            novoSaldo = account.getSaldo().add(req.valor());
        } else {
            if (account.getSaldo().compareTo(req.valor()) < 0) {
                throw new InsufficientFundsException("Saldo insuficiente para débito");
            }
            novoSaldo = account.getSaldo().subtract(req.valor());
        }

        account.setSaldo(novoSaldo);
        accountRepository.save(account);
        Transaction saved = transactionRepository.save(new Transaction(account.getId(), req.tipo(), req.valor()));
        return new TransactionResult(saved, novoSaldo);
    }

    public record TransactionResult(Transaction transaction, BigDecimal saldoAtual) {}
}
