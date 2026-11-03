package com.erpbanking.transactions.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.CreditSimulation;

public interface CreditSimulationRepository extends JpaRepository<CreditSimulation, Long> {
}
