package com.erpbanking.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "credit_requests")
public class CreditRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "valor_solicitado", nullable = false, precision = 19, scale = 2)
    private BigDecimal valorSolicitado;

    @Column(name = "prazo_meses", nullable = false)
    private int prazoMeses;

    @Column(name = "score_snapshot")
    private Double scoreSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditStatus status;

    @Column(name = "motivo")
    private String motivo;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm = Instant.now();

    public CreditRequest() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public BigDecimal getValorSolicitado() { return valorSolicitado; }
    public void setValorSolicitado(BigDecimal valorSolicitado) { this.valorSolicitado = valorSolicitado; }
    public int getPrazoMeses() { return prazoMeses; }
    public void setPrazoMeses(int prazoMeses) { this.prazoMeses = prazoMeses; }
    public Double getScoreSnapshot() { return scoreSnapshot; }
    public void setScoreSnapshot(Double scoreSnapshot) { this.scoreSnapshot = scoreSnapshot; }
    public CreditStatus getStatus() { return status; }
    public void setStatus(CreditStatus status) { this.status = status; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Instant getCriadoEm() { return criadoEm; }
    public void setCriadoEm(Instant criadoEm) { this.criadoEm = criadoEm; }
}
