package com.erpbanking.transactions.portal.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.domain.Transaction;
import com.erpbanking.transactions.domain.TransactionType;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.erpbanking.transactions.repository.TransactionRepository;
import com.erpbanking.transactions.service.AccountService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalSeederTest {

    @Mock AccountService accountService;
    @Mock AccountRepository accounts;
    @Mock SaleRepository sales;
    @Mock ReceivableRepository receivables;
    @Mock PayableRepository payables;
    @Mock InventoryItemRepository inventory;
    @Mock TransactionRepository transactions;

    private PortalSeeder seeder;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), PortalSeeder.ZONE);

    private final SeedProfile profile = SeedProfile.from(42, Map.of("segmento", "varejo",
            "respostas", Map.of("faturamento_mensal", 150000, "possui_estoque", true)));

    @BeforeEach
    void setUp() {
        seeder = new PortalSeeder(accountService, accounts, sales, receivables, payables, inventory, transactions,
                clock);
        when(sales.saveAll(anyList())).thenAnswer(inv -> {
            List<Sale> list = inv.getArgument(0);
            long id = 1000;
            for (Sale s : list) {
                s.setId(id++);
            }
            return new ArrayList<>(list);
        });
    }

    @Test
    void seedsNewPjCustomerOpeningTheAccount() {
        Account account = new Account(42L, BigDecimal.ZERO);
        account.setId(7L);
        when(sales.existsByCustomerId(42L)).thenReturn(false);
        when(accountService.openForNewCustomer(42L)).thenReturn(Optional.of(account));

        assertThat(seeder.seed(profile)).isEqualTo(PortalSeeder.Outcome.SEEDED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Receivable>> recs = ArgumentCaptor.forClass(List.class);
        verify(receivables).saveAll(recs.capture());
        assertThat(recs.getValue()).isNotEmpty().allSatisfy(r -> assertThat(r.getSaleId()).isGreaterThanOrEqualTo(1000));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> txs = ArgumentCaptor.forClass(List.class);
        verify(transactions).saveAll(txs.capture());
        assertThat(txs.getValue()).isNotEmpty().allSatisfy(t -> assertThat(t.getAccountId()).isEqualTo(7L));
        BigDecimal net = txs.getValue().stream()
                .map(t -> t.getTipo() == TransactionType.CREDIT ? t.getValor() : t.getValor().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(account.getSaldo()).isEqualByComparingTo(net).isPositive();
        verify(payables).saveAll(anyList());
        verify(inventory).saveAll(anyList());
        verify(accounts).save(account);
    }

    @Test
    void reusesExistingAccountAndKeepsItsBalance() {
        Account existing = new Account(42L, new BigDecimal("100.00"));
        existing.setId(3L);
        when(accounts.findIdsByCustomerId(42L)).thenReturn(List.of(3L));
        when(accounts.findByIdForUpdate(3L)).thenReturn(Optional.of(existing));

        assertThat(seeder.seed(profile)).isEqualTo(PortalSeeder.Outcome.SEEDED);

        assertThat(existing.getSaldo()).isGreaterThan(new BigDecimal("100.00"));
        verify(accounts).save(existing);
        verifyNoInteractions(accountService);
    }

    @Test
    void accountOpenedConcurrentlyIsLockedAndReused() {
        Account existing = new Account(42L, new BigDecimal("100.00"));
        existing.setId(3L);
        when(accounts.findIdsByCustomerId(42L)).thenReturn(List.of(), List.of(3L));
        when(accountService.openForNewCustomer(42L)).thenReturn(Optional.empty());
        when(accounts.findByIdForUpdate(3L)).thenReturn(Optional.of(existing));

        assertThat(seeder.seed(profile)).isEqualTo(PortalSeeder.Outcome.SEEDED);

        verify(accounts).findByIdForUpdate(3L);
        verify(accounts).save(existing);
    }

    private void existingAccount() {
        Account existing = new Account(42L, new BigDecimal("100.00"));
        existing.setId(3L);
        when(accounts.findIdsByCustomerId(42L)).thenReturn(List.of(3L));
        when(accounts.findByIdForUpdate(3L)).thenReturn(Optional.of(existing));
    }

    private void assertNotSeeded() {
        assertThat(seeder.seed(profile)).isEqualTo(PortalSeeder.Outcome.ALREADY_SEEDED);
        verify(accounts).findByIdForUpdate(3L);
        verifyNoInteractions(accountService, receivables, transactions);
        verify(sales, never()).saveAll(anyList());
        verify(payables, never()).saveAll(anyList());
        verify(inventory, never()).saveAll(anyList());
        verify(accounts, never()).save(any());
    }

    @Test
    void customerWithSalesIsNotSeededAgain() {
        existingAccount();
        when(sales.existsByCustomerId(42L)).thenReturn(true);
        assertNotSeeded();
    }

    @Test
    void customerWithPayablesOnlyIsNotSeededAgain() {
        existingAccount();
        when(payables.existsByCustomerId(42L)).thenReturn(true);
        assertNotSeeded();
    }

    @Test
    void customerWithInventoryOnlyIsNotSeededAgain() {
        existingAccount();
        when(inventory.existsByCustomerId(42L)).thenReturn(true);
        assertNotSeeded();
    }

    @Test
    void sameDayGivesSameData() {
        assertThat(new PortalDataGenerator().generate(profile, LocalDate.now(clock), clock.getZone()).saldo())
                .isEqualByComparingTo(new PortalDataGenerator().generate(profile, LocalDate.of(2026, 11, 3),
                        PortalSeeder.ZONE).saldo());
        assertThat(new PortalSeeder(accountService, accounts, sales, receivables, payables, inventory, transactions))
                .isNotNull();
    }
}
