package com.erpbanking.transactions.portal.seed;

import java.math.BigDecimal;
import java.util.List;

import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.domain.Transaction;

/**
 * Massa gerada para um cliente, ainda sem ids. Os recebíveis apontam para a venda pelo índice em {@code sales};
 * as transações ficam sem {@code accountId} e somam exatamente {@code saldo} (créditos − débitos).
 */
public record SeedData(
        List<Sale> sales,
        List<ReceivableDraft> receivables,
        List<Payable> payables,
        List<InventoryItem> inventory,
        List<Transaction> transactions,
        BigDecimal saldo) {

    /** Recebível ligado à venda {@code sales.get(saleIndex)}. */
    public record ReceivableDraft(int saleIndex, Receivable receivable) {}
}
