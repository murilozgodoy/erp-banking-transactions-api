package com.erpbanking.transactions.domain;

/**
 * Tipo de movimentação de estoque: {@code ENTRADA} soma, {@code SAIDA} subtrai e {@code AJUSTE} define a quantidade
 * final (inventário).
 */
public enum InventoryMovementType {
    ENTRADA,
    SAIDA,
    AJUSTE
}
