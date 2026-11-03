package com.erpbanking.transactions.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByCustomerIdOrderByIdDesc(Long customerId);

    boolean existsByCustomerId(Long customerId);

    List<Payment> findByCustomerIdAndStatusOrderByIdDesc(Long customerId, PaymentStatus status);

    /** Há pagamento no status para a conta a pagar (chamado com a conta a pagar já travada). */
    boolean existsByPayableIdAndStatus(Long payableId, PaymentStatus status);

    /** Ids dos pagamentos no status informado com data até {@code ate} (inclusive), mais antigos primeiro. */
    @Query("select p.id from Payment p where p.status = :status and p.agendadoPara <= :ate "
            + "order by p.agendadoPara, p.id")
    List<Long> findIdsDue(@Param("status") PaymentStatus status, @Param("ate") LocalDate ate);

    /** {@code SELECT ... FOR UPDATE}: um agendado só é executado uma vez, mesmo com várias instâncias. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);
}
