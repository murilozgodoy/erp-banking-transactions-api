package com.erpbanking.transactions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock AccountRepository accountRepo;
    @Mock TransactionRepository txRepo;
    @InjectMocks TransactionService service;

    private Account account;

    @BeforeEach
    void setup() {
        account = new Account(1L, new BigDecimal("100.00"));
        account.setId(10L);
    }

    @Test
    void creditIncreasesBalance() {
        when(accountRepo.findById(10L)).thenReturn(Optional.of(account));
        when(txRepo.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.register(
                new TransactionRequest(10L, TransactionType.CREDIT, new BigDecimal("50.00")));

        assertThat(result.saldoAtual()).isEqualByComparingTo("150.00");
        verify(accountRepo, times(1)).save(account);
    }

    @Test
    void debitDecreasesBalance() {
        when(accountRepo.findById(10L)).thenReturn(Optional.of(account));
        when(txRepo.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.register(
                new TransactionRequest(10L, TransactionType.DEBIT, new BigDecimal("30.00")));

        assertThat(result.saldoAtual()).isEqualByComparingTo("70.00");
    }

    @Test
    void debitFailsWithInsufficientFunds() {
        when(accountRepo.findById(10L)).thenReturn(Optional.of(account));
        assertThatThrownBy(() -> service.register(
                new TransactionRequest(10L, TransactionType.DEBIT, new BigDecimal("999"))))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void failsWhenAccountNotActive() {
        account.setStatus(AccountStatus.BLOCKED);
        when(accountRepo.findById(10L)).thenReturn(Optional.of(account));
        assertThatThrownBy(() -> service.register(
                new TransactionRequest(10L, TransactionType.CREDIT, new BigDecimal("10"))))
                .isInstanceOf(AccountBlockedException.class);
    }

    @Test
    void failsWhenAccountNotFound() {
        when(accountRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register(
                new TransactionRequest(99L, TransactionType.CREDIT, new BigDecimal("10"))))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
