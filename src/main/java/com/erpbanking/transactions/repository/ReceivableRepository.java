package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.erpbanking.transactions.domain.Receivable;

import jakarta.persistence.LockModeType;

public interface ReceivableRepository extends JpaRepository<Receivable, Long> {
    List<Receivable> findByCustomerIdOrderByVencimentoAsc(Long customerId);

    boolean existsByCustomerId(Long customerId);

    /** {@code SELECT ... FOR UPDATE} dos recebíveis do cliente, por vencimento: serializa as antecipações. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receivable r where r.customerId = :customerId order by r.vencimento, r.id")
    List<Receivable> findByCustomerIdForUpdate(@Param("customerId") Long customerId);
}
