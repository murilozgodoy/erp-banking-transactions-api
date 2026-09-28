package com.erpbanking.transactions.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erpbanking.transactions.dto.CreditRequestDto;
import com.erpbanking.transactions.dto.CreditResponseDto;
import com.erpbanking.transactions.service.CreditService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/credits")
public class CreditsController {

    private final CreditService creditService;

    public CreditsController(CreditService creditService) {
        this.creditService = creditService;
    }

    @PostMapping
    public ResponseEntity<CreditResponseDto> solicitar(@Valid @RequestBody CreditRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditService.solicitar(dto));
    }

    @GetMapping("/{id}")
    public CreditResponseDto consultar(@PathVariable Long id) {
        return creditService.consultar(id);
    }
}
