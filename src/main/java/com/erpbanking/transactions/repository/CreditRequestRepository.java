package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.CreditRequest;

public interface CreditRequestRepository extends JpaRepository<CreditRequest, Long> {
    List<CreditRequest> findByCustomerIdOrderByIdDesc(Long customerId);
}
