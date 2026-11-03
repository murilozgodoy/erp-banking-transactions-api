package com.erpbanking.transactions.portal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.erpbanking.transactions.repository.TransactionRepository;
import com.erpbanking.transactions.service.AccountService;

/** Grava a massa de verdade no H2 (mapeamento JPA, ids das vendas nos recebíveis e idempotência). */
@DataJpaTest
@Import({PortalSeeder.class, AccountService.class})
class PortalSeederJpaTest {

    @Autowired PortalSeeder seeder;
    @Autowired AccountRepository accounts;
    @Autowired SaleRepository sales;
    @Autowired ReceivableRepository receivables;
    @Autowired PayableRepository payables;
    @Autowired InventoryItemRepository inventory;
    @Autowired TransactionRepository transactions;

    @Test
    void persistsEverythingOnceAndOpensThePjAccount() {
        SeedProfile profile = SeedProfile.from(77, Map.of("segmento", "construcao", "respostas",
                Map.of("faturamento_mensal", "250000", "possui_estoque", true, "parcela_dividas_mensal", 8000)));

        assertThat(seeder.seed(profile)).isEqualTo(PortalSeeder.Outcome.SEEDED);
        long salesCount = sales.count();
        assertThat(seeder.seed(profile)).isEqualTo(PortalSeeder.Outcome.ALREADY_SEEDED);

        assertThat(sales.count()).isEqualTo(salesCount).isPositive();
        assertThat(accounts.findByCustomerIdOrderByIdDesc(77L)).hasSize(1);
        Account account = accounts.findByCustomerIdOrderByIdDesc(77L).get(0);
        assertThat(account.getSaldo()).isPositive();
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(account.getId())).isNotEmpty();
        assertThat(receivables.findByCustomerIdOrderByVencimentoAsc(77L)).isNotEmpty()
                .allSatisfy(r -> assertThat(sales.findById(r.getSaleId())).isPresent());
        assertThat(payables.existsByCustomerId(77L)).isTrue();
        assertThat(inventory.findByCustomerIdOrderBySkuAsc(77L)).hasSizeBetween(15, 40);
    }
}
