package com.erpbanking.transactions.domain;

public enum CreditStatus {
    APROVADO,
    NEGADO,
    REVISAO,
    /**
     * Pedido do portal feito por quem só tem {@code pedir_credito}: sem decisão, sem contrato e sem
     * {@code credit.decided} até alguém com {@code contratar_credito} confirmar.
     */
    AGUARDANDO_CONTRATACAO;

    /** {@code false} para AGUARDANDO_CONTRATACAO, que ainda não é uma decisão de crédito. */
    public boolean decisao() {
        return this != AGUARDANDO_CONTRATACAO;
    }
}
