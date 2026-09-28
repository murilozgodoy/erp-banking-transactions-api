package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccountIdOrderByTimestampDesc(Long accountId);
}
