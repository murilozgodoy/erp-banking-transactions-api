package com.erpbanking.transactions.service.lock;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fallback para o H2 (dev e testes), que não tem advisory lock: trava listrada dentro da JVM, por cliente, mantida
 * até o fim da transação Spring atual (liberada no {@code afterCompletion}, depois do commit/rollback). Só protege
 * uma instância da API; em produção quem garante é o PostgreSQL ({@link PostgresAdvisoryCustomerLock}).
 *
 * <p>Reentrante na mesma transação (o seeder trava e depois chama {@code openForNewCustomer}, que trava de novo):
 * cada listra é adquirida uma vez por transação e liberada uma vez.
 */
public class JvmStripedCustomerLock implements CustomerAccountLock {

    static final int STRIPES = 64;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    public JvmStripedCustomerLock() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    @Override
    public void lock(long customerId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Trava por cliente exige uma transação ativa");
        }
        ReentrantLock stripe = stripeFor(customerId);
        if (TransactionSynchronizationManager.hasResource(stripe)) {
            return;
        }
        stripe.lock();
        TransactionSynchronizationManager.bindResource(stripe, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(stripe);
                stripe.unlock();
            }
        });
    }

    ReentrantLock stripeFor(long customerId) {
        return stripes[Math.floorMod(Long.hashCode(customerId), STRIPES)];
    }
}
