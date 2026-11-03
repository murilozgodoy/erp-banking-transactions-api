package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Sale;

public interface SaleRepository extends JpaRepository<Sale, Long> {
    List<Sale> findByCustomerIdOrderByDataAsc(Long customerId);

    boolean existsByCustomerId(Long customerId);
}
