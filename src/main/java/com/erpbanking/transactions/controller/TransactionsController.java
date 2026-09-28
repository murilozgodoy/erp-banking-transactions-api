package com.erpbanking.transactions.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erpbanking.transactions.dto.TransactionRequest;
import com.erpbanking.transactions.dto.TransactionResponse;
import com.erpbanking.transactions.service.TransactionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/transactions")
public class TransactionsController {

    private final TransactionService transactionService;

    public TransactionsController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody TransactionRequest req) {
        var result = transactionService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponse.of(result.transaction(), result.saldoAtual()));
    }
}
