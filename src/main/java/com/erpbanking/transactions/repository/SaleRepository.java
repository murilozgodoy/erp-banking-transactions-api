package com.erpbanking.transactions.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Sale;

public interface SaleRepository extends JpaRepository<Sale, Long> {
    List<Sale> findByCustomerIdOrderByDataAsc(Long customerId);

    /** Vendas do cliente com {@code data} entre {@code from} e {@code to} (inclusive). */
    List<Sale> findByCustomerIdAndDataBetweenOrderByDataAsc(Long customerId, LocalDate from, LocalDate to);

    boolean existsByCustomerId(Long customerId);
}
