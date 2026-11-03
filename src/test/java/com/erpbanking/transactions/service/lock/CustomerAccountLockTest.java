package com.erpbanking.transactions.service.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

class CustomerAccountLockTest {

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void postgresTakesTransactionScopedAdvisoryLockOnTheCustomerKey() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("customerId", 42L)).thenReturn(query);

        new PostgresAdvisoryCustomerLock(em).lock(42L);

        verify(em).createNativeQuery(PostgresAdvisoryCustomerLock.SQL);
        verify(query).getSingleResult();
        assertThat(PostgresAdvisoryCustomerLock.SQL)
                .contains("pg_advisory_xact_lock(CAST(hashtext('conta:' || CAST(:customerId AS text)) AS bigint))");
    }

    @Test
    void selectsStrategyByDatasourceUrl() {
        EntityManager em = mock(EntityManager.class);
        assertThat(CustomerLockConfig.select("jdbc:postgresql://db:5432/erp?currentSchema=transactions", em))
                .isInstanceOf(PostgresAdvisoryCustomerLock.class);
        assertThat(CustomerLockConfig.select("jdbc:h2:mem:x;MODE=PostgreSQL", em))
                .isInstanceOf(JvmStripedCustomerLock.class);
        assertThat(CustomerLockConfig.select(null, em)).isInstanceOf(JvmStripedCustomerLock.class);
        assertThat(new CustomerLockConfig().customerAccountLock("jdbc:h2:mem:x", em))
                .isInstanceOf(JvmStripedCustomerLock.class);
        verifyNoInteractions(em);
    }

    @Test
    void jvmLockRequiresATransaction() {
        assertThatThrownBy(() -> new JvmStripedCustomerLock().lock(1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jvmLockIsHeldUntilTransactionCompletionAndIsReentrant() {
        JvmStripedCustomerLock lock = new JvmStripedCustomerLock();
        ReentrantLock stripe = lock.stripeFor(7L);
        TransactionSynchronizationManager.initSynchronization();

        lock.lock(7L);
        lock.lock(7L);

        assertThat(stripe.isHeldByCurrentThread()).isTrue();
        assertThat(stripe.getHoldCount()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        assertThat(stripe.isLocked()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(stripe)).isFalse();
    }
}
