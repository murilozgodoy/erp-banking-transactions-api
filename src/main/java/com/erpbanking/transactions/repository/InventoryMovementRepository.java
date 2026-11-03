package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.InventoryMovement;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    /** Histórico do item, mais recente primeiro (no máximo 100). */
    List<InventoryMovement> findTop100ByCustomerIdAndItemIdOrderByCreatedAtDescIdDesc(Long customerId, Long itemId);

    long countByItemId(Long itemId);
}
