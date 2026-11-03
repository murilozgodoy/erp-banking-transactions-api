package com.erpbanking.transactions.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.erpbanking.transactions.domain.CreditRequest;

import jakarta.persistence.LockModeType;

public interface CreditRequestRepository extends JpaRepository<CreditRequest, Long> {
    List<CreditRequest> findByCustomerIdOrderByIdDesc(Long customerId);

    /** {@code SELECT ... FOR UPDATE}: um pedido AGUARDANDO_CONTRATACAO é confirmado uma vez só. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CreditRequest r where r.id = :id")
    Optional<CreditRequest> findByIdForUpdate(@Param("id") Long id);
}
