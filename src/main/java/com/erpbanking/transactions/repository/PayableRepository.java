package com.erpbanking.transactions.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.erpbanking.transactions.domain.Payable;

import jakarta.persistence.LockModeType;

public interface PayableRepository extends JpaRepository<Payable, Long> {
    List<Payable> findByCustomerIdOrderByVencimentoAsc(Long customerId);

    boolean existsByCustomerId(Long customerId);

    /** {@code SELECT ... FOR UPDATE}: impede pagar a mesma conta duas vezes em paralelo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payable p where p.id = :id")
    Optional<Payable> findByIdForUpdate(@Param("id") Long id);
}
