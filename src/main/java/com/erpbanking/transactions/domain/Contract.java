package com.erpbanking.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "contracts")
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credit_request_id", nullable = false, unique = true)
    private Long creditRequestId;

    @Column(name = "taxa_juros", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxaJuros;

    @Column(name = "valor_aprovado", nullable = false, precision = 19, scale = 2)
    private BigDecimal valorAprovado;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm = Instant.now();

    public Contract() {}

    public Contract(Long creditRequestId, BigDecimal taxaJuros, BigDecimal valorAprovado) {
        this.creditRequestId = creditRequestId;
        this.taxaJuros = taxaJuros;
        this.valorAprovado = valorAprovado;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCreditRequestId() { return creditRequestId; }
    public void setCreditRequestId(Long creditRequestId) { this.creditRequestId = creditRequestId; }
    public BigDecimal getTaxaJuros() { return taxaJuros; }
    public void setTaxaJuros(BigDecimal taxaJuros) { this.taxaJuros = taxaJuros; }
    public BigDecimal getValorAprovado() { return valorAprovado; }
    public void setValorAprovado(BigDecimal valorAprovado) { this.valorAprovado = valorAprovado; }
    public Instant getCriadoEm() { return criadoEm; }
    public void setCriadoEm(Instant criadoEm) { this.criadoEm = criadoEm; }
}
