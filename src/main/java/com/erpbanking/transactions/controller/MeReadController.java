package com.erpbanking.transactions.controller;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.read.MeReadDtos;
import com.erpbanking.transactions.portal.read.PendenciasService;
import com.erpbanking.transactions.portal.read.PortalReadService;

/** Portal do cliente: leituras (GET /me/*). O customer vem do token, via Onboarding GET /me. */
@RestController
@RequestMapping("/me")
public class MeReadController {

    private final CurrentCustomerResolver customers;
    private final PortalReadService reads;
    private final PendenciasService pendencias;

    public MeReadController(CurrentCustomerResolver customers, PortalReadService reads, PendenciasService pendencias) {
        this.customers = customers;
        this.reads = reads;
        this.pendencias = pendencias;
    }

    @GetMapping("/overview")
    public MeReadDtos.Overview overview() {
        return reads.overview(customers.requireCustomerId());
    }

    @GetMapping("/sales")
    public MeReadDtos.Sales sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "group_by", required = false) String groupBy) {
        return reads.sales(customers.requireCustomerId(), from, to, groupBy);
    }

    @GetMapping("/receivables")
    public MeReadDtos.Receivables receivables(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reads.receivables(customers.requireCustomerId(), status, from, to);
    }

    @GetMapping("/payables")
    public MeReadDtos.Payables payables(@RequestParam(required = false) String status) {
        return reads.payables(customers.requireCustomerId(), status);
    }

    @GetMapping("/statement")
    public List<MeReadDtos.StatementEntry> statement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reads.statement(customers.requireCustomerId(), from, to);
    }

    @GetMapping("/inventory")
    public MeReadDtos.Inventory inventory() {
        return reads.inventory(customers.requireCustomerId());
    }

    @GetMapping("/pendencias")
    public List<MeReadDtos.Pendencia> pendencias() {
        return pendencias.pendencias(customers.requireCustomerId());
    }

    /** Parâmetro inválido (status, group_by, datas, from > to) → 400 {"error":"PARAMETRO_INVALIDO"}. */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> parametroInvalido(Exception e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "PARAMETRO_INVALIDO");
        body.put("mensagem", e instanceof MethodArgumentTypeMismatchException m
                ? "Valor inválido para " + m.getName() : e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
