package com.erpbanking.transactions.portal;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.erpbanking.transactions.domain.InventoryMovementType;
import com.erpbanking.transactions.domain.PaymentType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Métricas do portal do cliente em /actuator/prometheus (contrato com o Grafana):
 * {@code payments_total{tipo,status}}, {@code payments_amount_brl_total{tipo}},
 * {@code credit_simulations_total{produto,aprovacao}}, {@code credit_portal_requests_total{produto}} e
 * {@code inventory_movements_total{tipo}}.
 * Todas as séries são registradas com zero no startup.
 */
@Component
public class PortalMetrics {

    public static final String PAYMENTS = "payments";
    public static final String PAYMENTS_AMOUNT = "payments.amount.brl";
    public static final String CREDIT_SIMULATIONS = "credit.simulations";
    public static final String CREDIT_PORTAL_REQUESTS = "credit.portal.requests";
    public static final String INVENTORY_MOVEMENTS = "inventory.movements";

    public static final String EFETIVADO = "EFETIVADO";
    public static final String AGENDADO = "AGENDADO";
    public static final String RECUSADO = "RECUSADO";
    public static final String PENDENTE_APROVACAO = "PENDENTE_APROVACAO";
    public static final String REJEITADO = "REJEITADO";

    static final String[] PAYMENT_STATUSES = {EFETIVADO, AGENDADO, RECUSADO, PENDENTE_APROVACAO, REJEITADO};
    static final String[] PRODUTOS = {"ANTECIPACAO_RECEBIVEIS", "CAPITAL_DE_GIRO", "ALAVANCAGEM",
            "FINANCIAMENTO_ESTOQUE", "CONSOLIDACAO_DIVIDAS"};
    static final String[] APROVACOES = {"PRE_APROVADO", "ANALISE", "NEGADO"};

    private final MeterRegistry registry;

    public PortalMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (PaymentType tipo : PaymentType.values()) {
            for (String status : PAYMENT_STATUSES) {
                payments(tipo.name(), status);
            }
            amount(tipo.name());
        }
        for (String produto : PRODUTOS) {
            for (String aprovacao : APROVACOES) {
                simulations(produto, aprovacao);
            }
            requests(produto);
        }
        for (InventoryMovementType tipo : InventoryMovementType.values()) {
            movements(tipo);
        }
    }

    /** Movimentação de estoque gravada ({@code POST /me/inventory/{id}/movements}). */
    public void inventoryMovement(InventoryMovementType tipo) {
        movements(tipo).increment();
    }

    private Counter movements(InventoryMovementType tipo) {
        return Counter.builder(INVENTORY_MOVEMENTS).description("Movimentações de estoque pelo portal")
                .tag("tipo", tipo.name()).register(registry);
    }

    /**
     * Pagamento efetivado, agendado, recusado (saldo), pendente de aprovação (acima do limite) ou rejeitado; o valor
     * só soma nos efetivados.
     */
    public void payment(PaymentType tipo, String status, BigDecimal valor) {
        payments(tipo.name(), status).increment();
        if (EFETIVADO.equals(status) && valor != null) {
            amount(tipo.name()).increment(valor.doubleValue());
        }
    }

    public void simulation(String produto, String aprovacao) {
        simulations(produto, aprovacao).increment();
    }

    public void portalRequest(String produto) {
        requests(produto).increment();
    }

    private Counter payments(String tipo, String status) {
        return Counter.builder(PAYMENTS).description("Pagamentos do portal por tipo e status")
                .tag("tipo", tipo).tag("status", status).register(registry);
    }

    private Counter amount(String tipo) {
        return Counter.builder(PAYMENTS_AMOUNT).description("Valor (BRL) dos pagamentos efetivados no portal")
                .tag("tipo", tipo).register(registry);
    }

    private Counter simulations(String produto, String aprovacao) {
        return Counter.builder(CREDIT_SIMULATIONS).description("Simulações de crédito do portal")
                .tag("produto", produto).tag("aprovacao", aprovacao).register(registry);
    }

    private Counter requests(String produto) {
        return Counter.builder(CREDIT_PORTAL_REQUESTS).description("Pedidos de crédito feitos pelo portal")
                .tag("produto", produto).register(registry);
    }
}
