package com.erpbanking.transactions.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.erpbanking.transactions.domain.InventoryItem;

import jakarta.persistence.LockModeType;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findByCustomerIdOrderBySkuAsc(Long customerId);

    boolean existsByCustomerId(Long customerId);

    /** Item da empresa (vazio se não existe ou é de outra empresa). */
    Optional<InventoryItem> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * {@code SELECT ... FOR UPDATE} no item da empresa: serializa movimentações concorrentes do mesmo item. Filtra
     * pela empresa na própria consulta, então o item de outra empresa nem chega a ser travado.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.id = :id and i.customerId = :customerId")
    Optional<InventoryItem> findByIdAndCustomerIdForUpdate(@Param("id") Long id,
                                                          @Param("customerId") Long customerId);
}
