package com.erpbanking.transactions.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erpbanking.transactions.dto.CreditResponseDto;
import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.credit.CreditDtos.Offer;
import com.erpbanking.transactions.portal.credit.CreditDtos.PortalCreditRequest;
import com.erpbanking.transactions.portal.credit.CreditDtos.SimulationRequest;
import com.erpbanking.transactions.portal.credit.CreditDtos.SimulationResponse;
import com.erpbanking.transactions.portal.credit.PortalCreditService;

import jakarta.validation.Valid;

/** Portal do cliente: ofertas, simulador e pedidos de crédito. */
@RestController
@RequestMapping("/me/credit")
public class MeCreditController {

    private final CurrentCustomerResolver currentCustomer;
    private final PortalCreditService credit;

    public MeCreditController(CurrentCustomerResolver currentCustomer, PortalCreditService credit) {
        this.currentCustomer = currentCustomer;
        this.credit = credit;
    }

    @GetMapping("/offers")
    public List<Offer> offers() {
        return credit.offers(currentCustomer.requireCustomerId());
    }

    @PostMapping("/simulations")
    public ResponseEntity<SimulationResponse> simulate(@Valid @RequestBody SimulationRequest req) {
        Long customerId = currentCustomer.requireCustomerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(credit.simulate(customerId, req));
    }

    @PostMapping("/requests")
    public ResponseEntity<CreditResponseDto> request(@Valid @RequestBody PortalCreditRequest req) {
        Long customerId = currentCustomer.requireCustomerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(credit.request(customerId, req));
    }

    @GetMapping("/requests")
    public List<CreditResponseDto> requests() {
        return credit.requests(currentCustomer.requireCustomerId());
    }
}
