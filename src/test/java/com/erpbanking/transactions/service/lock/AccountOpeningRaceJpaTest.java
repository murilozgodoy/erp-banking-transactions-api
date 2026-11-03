package com.erpbanking.transactions.service.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import com.erpbanking.transactions.service.AccountService;

/**
 * Abertura automática de conta no H2: a estratégia escolhida é o fallback na JVM (sem SQL de advisory lock) e duas
 * entregas paralelas para o mesmo cliente sem conta terminam com exatamente uma conta. Em produção a garantia vem
 * do {@code pg_advisory_xact_lock} do PostgreSQL ({@link PostgresAdvisoryCustomerLock}), que vale entre instâncias.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:account-opening-race;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@Import({AccountService.class, CustomerLockConfig.class})
class AccountOpeningRaceJpaTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired CustomerAccountLock customerLock;

    @Test
    void h2UsesTheJvmFallbackAndOpensOnceInTheSameTransaction() {
        assertThat(customerLock).isInstanceOf(JvmStripedCustomerLock.class);

        assertThat(accountService.openForNewCustomer(500L)).isPresent();
        assertThat(accountService.openForNewCustomer(500L)).isEmpty();
        assertThat(accounts.findByCustomerIdOrderByIdDesc(500L)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void parallelOpeningsForCustomerWithoutAccountEndWithExactlyOne() throws Exception {
        long customerId = 501L;
        int threads = 2;
        for (int round = 0; round < 5; round++) {
            long id = customerId + round * 1000L;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<Account>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return accountService.openForNewCustomer(id);
                }));
            }
            start.countDown();
            long opened = 0;
            for (Future<Optional<Account>> f : futures) {
                if (f.get(30, TimeUnit.SECONDS).isPresent()) {
                    opened++;
                }
            }
            pool.shutdownNow();

            assertThat(opened).isEqualTo(1);
            assertThat(accounts.findByCustomerIdOrderByIdDesc(id)).hasSize(1);
        }
    }
}
