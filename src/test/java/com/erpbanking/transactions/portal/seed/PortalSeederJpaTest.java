package com.erpbanking.transactions.portal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.erpbanking.transactions.repository.TransactionRepository;
import com.erpbanking.transactions.service.AccountService;
import com.erpbanking.transactions.service.lock.CustomerLockConfig;

/**
 * Grava a massa de verdade no H2 (mapeamento JPA, ids das vendas nos recebíveis e idempotência).
 *
 * <p>Usa um H2 próprio com {@code LOCK_TIMEOUT=30000}: o banco embutido padrão do {@code @DataJpaTest}
 * espera só ~2 s por um lock de linha, e no teste paralelo as entregas que perdem a corrida ficam
 * paradas no {@code SELECT ... FOR UPDATE} da conta enquanto a vencedora grava a massa inteira numa
 * transação. Sob carga isso passava de 2 s e estourava "Timeout trying to lock table ACCOUNTS".
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:portal-seeder-jpa;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@Import({PortalSeeder.class, AccountService.class, CustomerLockConfig.class})
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

    /** Entregas paralelas do mesmo evento: o lock da conta serializa e só uma popula. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void parallelDeliveriesSeedOnlyOnce() throws Exception {
        accounts.save(new Account(78L, new BigDecimal("100.00")));
        SeedProfile profile = SeedProfile.from(78, Map.of("segmento", "varejo",
                "respostas", Map.of("faturamento_mensal", 90000, "possui_estoque", true)));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PortalSeeder.Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return seeder.seed(profile);
            }));
        }
        start.countDown();
        List<PortalSeeder.Outcome> outcomes = new ArrayList<>();
        for (Future<PortalSeeder.Outcome> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(outcomes).containsOnlyOnce(PortalSeeder.Outcome.SEEDED);
        Account account = accounts.findByCustomerIdOrderByIdDesc(78L).get(0);
        long credits = transactions.findByAccountIdOrderByTimestampDescIdDesc(account.getId()).size();
        assertThat(credits).isPositive();
        long salesOf78 = sales.findAll().stream().filter(s -> s.getCustomerId() == 78L).count();
        assertThat(salesOf78).isEqualTo(new PortalDataGenerator()
                .generate(profile, LocalDate.now(PortalSeeder.ZONE), PortalSeeder.ZONE).sales().size());
    }
}
