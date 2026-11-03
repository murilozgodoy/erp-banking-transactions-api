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

import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.inventory.InventoryDtos.MovementCreated;
import com.erpbanking.transactions.portal.inventory.InventoryDtos.MovementRequest;
import com.erpbanking.transactions.portal.inventory.InventoryDtos.MovementView;
import com.erpbanking.transactions.portal.inventory.InventoryMovementService;

import jakarta.validation.Valid;

/** Portal do cliente: movimentação de estoque (entrada, saída e ajuste) e histórico por item. */
@RestController
@RequestMapping("/me/inventory/{id}/movements")
public class MeInventoryController {

    private final CurrentCustomerResolver currentCustomer;
    private final InventoryMovementService movements;

    public MeInventoryController(CurrentCustomerResolver currentCustomer, InventoryMovementService movements) {
        this.currentCustomer = currentCustomer;
        this.movements = movements;
    }

    @PostMapping
    public ResponseEntity<MovementCreated> move(@PathVariable Long id, @Valid @RequestBody MovementRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(movements.move(currentCustomer.requirePermission(Permissoes.GERENCIAR_ESTOQUE), id, req));
    }

    @GetMapping
    public List<MovementView> history(@PathVariable Long id) {
        return movements.history(currentCustomer.requirePermission(Permissoes.VER_FINANCEIRO).customerId(), id);
    }
}
