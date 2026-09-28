package com.erpbanking.transactions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.dto.AccountCreateRequest;
import com.erpbanking.transactions.exception.ApiExceptions.AccountNotFoundException;
import com.erpbanking.transactions.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository repo;
    @InjectMocks AccountService service;

    @Test
    void createPersistsWithInitialBalance() {
        when(repo.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });

        Account result = service.create(new AccountCreateRequest(42L, new BigDecimal("100.00")));

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getCustomerId()).isEqualTo(42L);
        assertThat(result.getSaldo()).isEqualByComparingTo("100.00");
    }

    @Test
    void createWithNullBalanceDefaultsToZero() {
        when(repo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        Account result = service.create(new AccountCreateRequest(42L, null));
        assertThat(result.getSaldo()).isEqualByComparingTo("0");
    }

    @Test
    void getReturnsAccountWhenFound() {
        Account a = new Account(1L, new BigDecimal("50"));
        a.setId(5L);
        when(repo.findById(5L)).thenReturn(Optional.of(a));

        assertThat(service.get(5L).getId()).isEqualTo(5L);
    }

    @Test
    void getThrowsWhenNotFound() {
        when(repo.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
