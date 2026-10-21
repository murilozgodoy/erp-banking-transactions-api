package com.erpbanking.transactions.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.erpbanking.transactions.dto.AccountCreateRequest;
import com.erpbanking.transactions.dto.AccountResponse;
import com.erpbanking.transactions.dto.TransactionResponse;
import com.erpbanking.transactions.service.AccountService;
import com.erpbanking.transactions.service.TransactionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
public class AccountsController {

    private final AccountService accountService;
    private final TransactionService transactionService;

    public AccountsController(AccountService accountService, TransactionService transactionService) {
        this.accountService = accountService;
        this.transactionService = transactionService;
    }

    @GetMapping
    public List<AccountResponse> list(@RequestParam(required = false) Long customerId) {
        return accountService.list(customerId).stream().map(AccountResponse::of).toList();
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody AccountCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.of(accountService.create(req)));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return AccountResponse.of(accountService.get(id));
    }

    @GetMapping("/{id}/transactions")
    public List<TransactionResponse> statement(@PathVariable Long id) {
        return transactionService.statement(id);
    }
}
