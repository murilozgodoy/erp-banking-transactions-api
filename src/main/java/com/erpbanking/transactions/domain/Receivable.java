package com.erpbanking.transactions.domain;

import java.math.BigDecimal;
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

/** Recebível (agenda de cartão) derivado de uma venda. */
@Entity
@Table(name = "receivables", indexes = @Index(name = "idx_receivables_customer", columnList = "customer_id"))
public class Receivable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "sale_id")
    private Long saleId;

    @Column(name = "vencimento", nullable = false)
    private LocalDate vencimento;

    @Column(name = "valor_bruto", nullable = false, precision = 19, scale = 2)
    private BigDecimal valorBruto;

    @Column(name = "taxa_adquirente", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxaAdquirente;

    @Column(name = "valor_liquido", nullable = false, precision = 19, scale = 2)
    private BigDecimal valorLiquido;

    @Column(name = "adquirente", nullable = false)
    private String adquirente;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReceivableStatus status;

    public Receivable() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Long getSaleId() { return saleId; }
    public void setSaleId(Long saleId) { this.saleId = saleId; }
    public LocalDate getVencimento() { return vencimento; }
    public void setVencimento(LocalDate vencimento) { this.vencimento = vencimento; }
    public BigDecimal getValorBruto() { return valorBruto; }
    public void setValorBruto(BigDecimal valorBruto) { this.valorBruto = valorBruto; }
    public BigDecimal getTaxaAdquirente() { return taxaAdquirente; }
    public void setTaxaAdquirente(BigDecimal taxaAdquirente) { this.taxaAdquirente = taxaAdquirente; }
    public BigDecimal getValorLiquido() { return valorLiquido; }
    public void setValorLiquido(BigDecimal valorLiquido) { this.valorLiquido = valorLiquido; }
    public String getAdquirente() { return adquirente; }
    public void setAdquirente(String adquirente) { this.adquirente = adquirente; }
    public ReceivableStatus getStatus() { return status; }
    public void setStatus(ReceivableStatus status) { this.status = status; }
}
