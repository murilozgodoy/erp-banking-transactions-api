package com.erpbanking.transactions.repository;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.erpbanking.transactions.domain.CreditSimulation;
import com.erpbanking.transactions.domain.CreditStatus;

import jakarta.persistence.LockModeType;

public interface CreditSimulationRepository extends JpaRepository<CreditSimulation, Long> {

    /** {@code SELECT ... FOR UPDATE}: uma simulação vira no máximo um pedido, mesmo com cliques em paralelo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CreditSimulation s where s.id = :id")
    Optional<CreditSimulation> findByIdForUpdate(@Param("id") Long id);

    /** Soma do valor das simulações do produto cujo pedido está no status informado; {@code null} se nenhuma. */
    @Query("select sum(s.valor) from CreditSimulation s, CreditRequest r "
            + "where r.id = s.creditRequestId and s.customerId = :customerId and s.produto = :produto "
            + "and r.status = :status")
    BigDecimal sumValorByRequestStatus(@Param("customerId") Long customerId, @Param("produto") String produto,
                                       @Param("status") CreditStatus status);
}
