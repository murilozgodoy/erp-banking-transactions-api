package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Receivable;

public interface ReceivableRepository extends JpaRepository<Receivable, Long> {
    List<Receivable> findByCustomerIdOrderByVencimentoAsc(Long customerId);

    boolean existsByCustomerId(Long customerId);
}
