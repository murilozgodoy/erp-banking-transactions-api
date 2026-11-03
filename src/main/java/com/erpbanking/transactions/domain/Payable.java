package com.erpbanking.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Conta a pagar da empresa cliente. */
@Entity
@Table(name = "payables", indexes = @Index(name = "idx_payables_customer", columnList = "customer_id"))
public class Payable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "fornecedor", nullable = false)
    private String fornecedor;

    @Enumerated(EnumType.STRING)
    @Column(name = "categoria", nullable = false)
    private PayableCategory categoria;

    @Column(name = "vencimento", nullable = false)
    private LocalDate vencimento;

    @Column(name = "valor", nullable = false, precision = 19, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PayableStatus status;

    @Column(name = "codigo_barras")
    private String codigoBarras;

    @Column(name = "pago_em")
    private Instant pagoEm;

    public Payable() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getFornecedor() { return fornecedor; }
    public void setFornecedor(String fornecedor) { this.fornecedor = fornecedor; }
    public PayableCategory getCategoria() { return categoria; }
    public void setCategoria(PayableCategory categoria) { this.categoria = categoria; }
    public LocalDate getVencimento() { return vencimento; }
    public void setVencimento(LocalDate vencimento) { this.vencimento = vencimento; }
    public BigDecimal getValor() { return valor; }
    public void setValor(BigDecimal valor) { this.valor = valor; }
    public PayableStatus getStatus() { return status; }
    public void setStatus(PayableStatus status) { this.status = status; }
    public String getCodigoBarras() { return codigoBarras; }
    public void setCodigoBarras(String codigoBarras) { this.codigoBarras = codigoBarras; }
    public Instant getPagoEm() { return pagoEm; }
    public void setPagoEm(Instant pagoEm) { this.pagoEm = pagoEm; }
}
