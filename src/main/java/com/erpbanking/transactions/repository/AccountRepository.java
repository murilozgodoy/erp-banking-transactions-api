package com.erpbanking.transactions.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erpbanking.transactions.domain.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCustomerIdOrderByIdDesc(Long customerId);
}
