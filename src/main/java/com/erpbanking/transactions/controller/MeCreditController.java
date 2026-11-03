package com.erpbanking.transactions.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erpbanking.transactions.dto.CreditResponseDto;
import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.credit.CreditDtos.Offer;
import com.erpbanking.transactions.portal.credit.CreditDtos.PortalCreditRequest;
import com.erpbanking.transactions.portal.credit.CreditDtos.SimulationRequest;
import com.erpbanking.transactions.portal.credit.CreditDtos.SimulationResponse;
import com.erpbanking.transactions.portal.credit.PortalCreditService;

import jakarta.validation.Valid;

/**
 * Portal do cliente: ofertas, simulador e pedidos de crédito. GET exige {@code ver_financeiro}; simular exige
 * {@code pedir_credito}; pedir exige {@code contratar_credito} (ou {@code pedir_credito}, e o pedido fica
 * AGUARDANDO_CONTRATACAO); confirmar exige {@code contratar_credito}.
 */
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
        return credit.offers(currentCustomer.requirePermission(Permissoes.VER_FINANCEIRO).customerId());
    }

    @PostMapping("/simulations")
    public ResponseEntity<SimulationResponse> simulate(@Valid @RequestBody SimulationRequest req) {
        Long customerId = currentCustomer.requirePermission(Permissoes.PEDIR_CREDITO).customerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(credit.simulate(customerId, req));
    }

    /** A permissão (contratar ou só pedir) é decidida no serviço. */
    @PostMapping("/requests")
    public ResponseEntity<CreditResponseDto> request(@Valid @RequestBody PortalCreditRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credit.request(currentCustomer.require(), req));
    }

    @PostMapping("/requests/{id}/confirm")
    public CreditResponseDto confirm(@PathVariable Long id) {
        return credit.confirm(currentCustomer.requirePermission(Permissoes.CONTRATAR_CREDITO), id);
    }

    @GetMapping("/requests")
    public List<CreditResponseDto> requests() {
        return credit.requests(currentCustomer.requirePermission(Permissoes.VER_FINANCEIRO).customerId());
    }
}
