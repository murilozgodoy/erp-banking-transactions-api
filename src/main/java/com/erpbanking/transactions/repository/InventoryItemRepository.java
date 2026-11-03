package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.InventoryItem;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findByCustomerIdOrderBySkuAsc(Long customerId);

    boolean existsByCustomerId(Long customerId);
}
