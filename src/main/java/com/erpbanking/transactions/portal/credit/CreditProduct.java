package com.erpbanking.transactions.portal.credit;

import java.math.BigDecimal;

/**
 * Produtos de crédito do portal (mesmos nomes das {@code oportunidades} do Score), com o ajuste de taxa
 * em pontos percentuais sobre a taxa base da faixa e o prazo máximo.
 */
public enum CreditProduct {
    ANTECIPACAO_RECEBIVEIS("-0.40", 6, true),
    CAPITAL_DE_GIRO("0.00", 24, true),
    ALAVANCAGEM("0.30", 36, true),
    FINANCIAMENTO_ESTOQUE("-0.20", 18, true),
    /** Precificada e limitada como capital de giro; não aparece nas ofertas. */
    CONSOLIDACAO_DIVIDAS("0.00", 24, false);

    private final BigDecimal ajustePp;
    private final int prazoMaxMeses;
    private final boolean ofertado;

    CreditProduct(String ajustePp, int prazoMaxMeses, boolean ofertado) {
        this.ajustePp = new BigDecimal(ajustePp);
        this.prazoMaxMeses = prazoMaxMeses;
        this.ofertado = ofertado;
    }

    public BigDecimal ajustePp() {
        return ajustePp;
    }

    public int prazoMaxMeses() {
        return prazoMaxMeses;
    }

    public boolean ofertado() {
        return ofertado;
    }
}
