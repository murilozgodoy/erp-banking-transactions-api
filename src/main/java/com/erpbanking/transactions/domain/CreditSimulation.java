package com.erpbanking.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Simulação de crédito do portal; guarda o necessário para virar pedido ({@code simulation_id}). */
@Entity
@Table(name = "credit_simulations", indexes = @Index(name = "idx_credit_simulations_customer", columnList = "customer_id"))
public class CreditSimulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "produto", nullable = false)
    private String produto;

    @Column(name = "valor", nullable = false, precision = 19, scale = 2)
    private BigDecimal valor;

    @Column(name = "prazo_meses", nullable = false)
    private int prazoMeses;

    /** Percentual ao mês (ex.: 2.4900). */
    @Column(name = "taxa_mensal", nullable = false, precision = 9, scale = 4)
    private BigDecimal taxaMensal;

    @Column(name = "faixa_risco")
    private String faixaRisco;

    @Column(name = "score")
    private Double score;

    @Column(name = "aprovacao", nullable = false)
    private String aprovacao;

    @Column(name = "limite", nullable = false, precision = 19, scale = 2)
    private BigDecimal limite;

    /** Ids dos recebíveis selecionados, separados por vírgula (antecipação). */
    @Column(name = "receivable_ids", length = 4000)
    private String receivableIds;

    @Column(name = "validade", nullable = false)
    private LocalDate validade;

    @Column(name = "credit_request_id")
    private Long creditRequestId;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm = Instant.now();

    public CreditSimulation() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getProduto() { return produto; }
    public void setProduto(String produto) { this.produto = produto; }
    public BigDecimal getValor() { return valor; }
    public void setValor(BigDecimal valor) { this.valor = valor; }
    public int getPrazoMeses() { return prazoMeses; }
    public void setPrazoMeses(int prazoMeses) { this.prazoMeses = prazoMeses; }
    public BigDecimal getTaxaMensal() { return taxaMensal; }
    public void setTaxaMensal(BigDecimal taxaMensal) { this.taxaMensal = taxaMensal; }
    public String getFaixaRisco() { return faixaRisco; }
    public void setFaixaRisco(String faixaRisco) { this.faixaRisco = faixaRisco; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getAprovacao() { return aprovacao; }
    public void setAprovacao(String aprovacao) { this.aprovacao = aprovacao; }
    public BigDecimal getLimite() { return limite; }
    public void setLimite(BigDecimal limite) { this.limite = limite; }
    public String getReceivableIds() { return receivableIds; }
    public void setReceivableIds(String receivableIds) { this.receivableIds = receivableIds; }
    public LocalDate getValidade() { return validade; }
    public void setValidade(LocalDate validade) { this.validade = validade; }
    public Long getCreditRequestId() { return creditRequestId; }
    public void setCreditRequestId(Long creditRequestId) { this.creditRequestId = creditRequestId; }
    public Instant getCriadoEm() { return criadoEm; }
    public void setCriadoEm(Instant criadoEm) { this.criadoEm = criadoEm; }
}
