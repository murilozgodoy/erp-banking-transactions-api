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
import com.erpbanking.transactions.portal.Permissoes;
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
        return reads.overview(customerId());
    }

    @GetMapping("/sales")
    public MeReadDtos.Sales sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "group_by", required = false) String groupBy) {
        return reads.sales(customerId(), from, to, groupBy);
    }

    @GetMapping("/receivables")
    public MeReadDtos.Receivables receivables(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reads.receivables(customerId(), status, from, to);
    }

    @GetMapping("/payables")
    public MeReadDtos.Payables payables(@RequestParam(required = false) String status) {
        return reads.payables(customerId(), status);
    }

    @GetMapping("/statement")
    public List<MeReadDtos.StatementEntry> statement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reads.statement(customerId(), from, to);
    }

    @GetMapping("/inventory")
    public MeReadDtos.Inventory inventory() {
        return reads.inventory(customerId());
    }

    @GetMapping("/pendencias")
    public List<MeReadDtos.Pendencia> pendencias() {
        return pendencias.pendencias(customerId());
    }

    /** Todas as leituras exigem {@code ver_financeiro}. */
    private Long customerId() {
        return customers.requirePermission(Permissoes.VER_FINANCEIRO).customerId();
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
