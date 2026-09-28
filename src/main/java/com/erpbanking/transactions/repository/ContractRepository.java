package com.erpbanking.transactions.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Contract;

public interface ContractRepository extends JpaRepository<Contract, Long> {
    Optional<Contract> findByCreditRequestId(Long creditRequestId);
}
