package com.erpbanking.transactions.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByCustomerId(Long customerId);
}
