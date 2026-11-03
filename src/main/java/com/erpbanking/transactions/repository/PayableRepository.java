package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Payable;

public interface PayableRepository extends JpaRepository<Payable, Long> {
    List<Payable> findByCustomerIdOrderByVencimentoAsc(Long customerId);

    boolean existsByCustomerId(Long customerId);
}
