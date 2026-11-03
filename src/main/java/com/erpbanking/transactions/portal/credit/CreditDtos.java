package com.erpbanking.transactions.portal.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.erpbanking.transactions.portal.credit.CreditPricing.Aprovacao;
import com.erpbanking.transactions.portal.credit.CreditPricing.Parcela;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Contratos JSON (snake_case) de {@code /me/credit}. Taxas em %, escala 4; valores em BRL. */
public final class CreditDtos {

    private CreditDtos() {}

    /** Corpo de {@code POST /me/credit/simulations}. */
    public record SimulationRequest(
            @NotNull CreditProduct produto,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
            @JsonProperty("prazo_meses") @NotNull @Min(1) Integer prazoMeses,
            @JsonProperty("receivable_ids") @Size(max = 500) List<Long> receivableIds) {}

    /** Resposta de {@code POST /me/credit/simulations}. */
    public record SimulationResponse(
            Long id,
            CreditProduct produto,
            @JsonProperty("valor_solicitado") BigDecimal valorSolicitado,
            @JsonProperty("valor_liquido") BigDecimal valorLiquido,
            @JsonProperty("taxa_mensal") BigDecimal taxaMensal,
            @JsonProperty("cet_anual") BigDecimal cetAnual,
            BigDecimal iof,
            List<ParcelaView> parcelas,
            @JsonProperty("total_pago") BigDecimal totalPago,
            Aprovacao aprovacao,
            @JsonProperty("limite_disponivel") BigDecimal limiteDisponivel,
            LocalDate validade) {}

    public record ParcelaView(int n, LocalDate vencimento, BigDecimal amortizacao, BigDecimal juros,
                              BigDecimal valor) {
        static ParcelaView of(Parcela p) {
            return new ParcelaView(p.n(), p.vencimento(), p.amortizacao(), p.juros(), p.valor());
        }
    }

    /** Item de {@code GET /me/credit/offers}. */
    public record Offer(
            CreditProduct produto,
            BigDecimal limite,
            @JsonProperty("taxa_mensal_a_partir") BigDecimal taxaMensalAPartir,
            @JsonProperty("prazo_max_meses") int prazoMaxMeses) {}

    /** Corpo de {@code POST /me/credit/requests}: {@code simulation_id} ou {@code produto, valor, prazo_meses}. */
    public record PortalCreditRequest(
            @JsonProperty("simulation_id") Long simulationId,
            CreditProduct produto,
            @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
            @JsonProperty("prazo_meses") @Min(1) Integer prazoMeses) {}
}
