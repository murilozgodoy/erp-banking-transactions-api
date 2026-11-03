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
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Movimentação de um item de estoque ({@code POST /me/inventory/{id}/movements}). Para {@code AJUSTE},
 * {@code quantidade} é a quantidade final informada; nos demais, a quantidade movimentada.
 */
@Entity
@Table(name = "inventory_movements", indexes = @Index(name = "idx_inventory_movements_customer_item_created",
        columnList = "customer_id, item_id, created_at"))
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 10)
    private InventoryMovementType tipo;

    @Column(name = "quantidade", nullable = false)
    private Integer quantidade;

    @Column(name = "quantidade_anterior", nullable = false)
    private Integer quantidadeAnterior;

    @Column(name = "quantidade_final", nullable = false)
    private Integer quantidadeFinal;

    @Column(name = "custo_unitario", precision = 19, scale = 2)
    private BigDecimal custoUnitario;

    @Column(name = "motivo", length = 200)
    private String motivo;

    @Column(name = "created_by_sub")
    private String createdBySub;

    @Column(name = "created_by_nome")
    private String createdByNome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public InventoryMovement() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public InventoryMovementType getTipo() { return tipo; }
    public void setTipo(InventoryMovementType tipo) { this.tipo = tipo; }
    public Integer getQuantidade() { return quantidade; }
    public void setQuantidade(Integer quantidade) { this.quantidade = quantidade; }
    public Integer getQuantidadeAnterior() { return quantidadeAnterior; }
    public void setQuantidadeAnterior(Integer quantidadeAnterior) { this.quantidadeAnterior = quantidadeAnterior; }
    public Integer getQuantidadeFinal() { return quantidadeFinal; }
    public void setQuantidadeFinal(Integer quantidadeFinal) { this.quantidadeFinal = quantidadeFinal; }
    public BigDecimal getCustoUnitario() { return custoUnitario; }
    public void setCustoUnitario(BigDecimal custoUnitario) { this.custoUnitario = custoUnitario; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public String getCreatedBySub() { return createdBySub; }
    public void setCreatedBySub(String createdBySub) { this.createdBySub = createdBySub; }
    public String getCreatedByNome() { return createdByNome; }
    public void setCreatedByNome(String createdByNome) { this.createdByNome = createdByNome; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
