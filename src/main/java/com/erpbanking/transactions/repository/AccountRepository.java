package com.erpbanking.transactions.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.erpbanking.transactions.domain.Account;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCustomerIdOrderByIdDesc(Long customerId);

    /** Só os ids (mais recente primeiro): não carrega a entidade antes do lock. */
    @Query("select a.id from Account a where a.customerId = :customerId order by a.id desc")
    List<Long> findIdsByCustomerId(@Param("customerId") Long customerId);

    /** {@code SELECT ... FOR UPDATE}: serializa débitos/créditos concorrentes na mesma conta. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
