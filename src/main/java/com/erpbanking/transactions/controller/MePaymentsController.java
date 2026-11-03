package com.erpbanking.transactions.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentCreated;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentRequest;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentView;
import com.erpbanking.transactions.portal.payment.PortalPaymentService;

import jakarta.validation.Valid;

/** Portal do cliente: pagar (PIX, boleto, TED), agendar e consultar o histórico. */
@RestController
@RequestMapping("/me/payments")
public class MePaymentsController {

    private final CurrentCustomerResolver currentCustomer;
    private final PortalPaymentService payments;

    public MePaymentsController(CurrentCustomerResolver currentCustomer, PortalPaymentService payments) {
        this.currentCustomer = currentCustomer;
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<PaymentCreated> pay(@Valid @RequestBody PaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payments.pay(currentCustomer.requirePermission(Permissoes.PAGAR), req));
    }

    @GetMapping
    public List<PaymentView> history() {
        return payments.history(currentCustomer.requirePermission(Permissoes.VER_FINANCEIRO).customerId());
    }
}
