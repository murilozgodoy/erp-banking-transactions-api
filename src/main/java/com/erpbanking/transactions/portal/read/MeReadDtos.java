package com.erpbanking.transactions.portal.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Respostas das rotas GET /me (contrato em PORTAL_CLIENTE.md, seção 5). JSON em snake_case. */
public final class MeReadDtos {

    private MeReadDtos() {}

    // ---------- /me/overview ----------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Conta(Long id, String agencia, String numero) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FluxoCaixaDia(LocalDate data, BigDecimal entradas, BigDecimal saidas, BigDecimal saldoProjetado) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Overview(
            BigDecimal saldo,
            Conta conta,
            @JsonProperty("receita_30d") BigDecimal receita30d,
            @JsonProperty("receita_30d_variacao_pct") BigDecimal receita30dVariacaoPct,
            @JsonProperty("ticket_medio_30d") BigDecimal ticketMedio30d,
            @JsonProperty("recebiveis_90d") BigDecimal recebiveis90d,
            @JsonProperty("a_pagar_30d") BigDecimal aPagar30d,
            BigDecimal atrasadosTotal,
            BigDecimal estoqueValor,
            int pendenciasCount,
            List<FluxoCaixaDia> fluxoCaixa) {}

    // ---------- /me/sales ----------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SeriePonto(String periodo, BigDecimal valor, long quantidade) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PorForma(String forma, BigDecimal valor, BigDecimal pct) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PorCanal(String canal, BigDecimal valor, BigDecimal pct) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TopProduto(String sku, String nome, BigDecimal valor, long quantidade) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Sales(List<SeriePonto> serie, List<PorForma> porFormaPagamento, List<PorCanal> porCanal,
                        List<TopProduto> topProdutos, BigDecimal total, BigDecimal ticketMedio) {}

    // ---------- /me/receivables ----------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReceivableItem(Long id, LocalDate vencimento, BigDecimal valorBruto, BigDecimal valorLiquido,
                                 String adquirente, String status) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Semana(LocalDate semana, BigDecimal valor) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Receivables(List<ReceivableItem> itens, BigDecimal totalPrevisto, List<Semana> porSemana) {}

    // ---------- /me/payables ----------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PayableItem(Long id, String fornecedor, String categoria, LocalDate vencimento, BigDecimal valor,
                              String status, String codigoBarras, Instant pagoEm) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Payables(List<PayableItem> itens, BigDecimal totalAberto, BigDecimal totalAtrasado,
                           @JsonProperty("proximos_7d") BigDecimal proximos7d) {}

    // ---------- /me/statement ----------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatementEntry(Long id, Instant data, String tipo, BigDecimal valor, BigDecimal saldoApos,
                                 String descricao) {}

    // ---------- /me/inventory ----------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InventoryRow(Long id, String sku, String nome, int quantidade, int minimo,
                               BigDecimal custoUnitario, BigDecimal precoVenda, int giroDias, LocalDate ultimaVenda,
                               int coberturaDias, boolean abaixoMinimo, boolean parado) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Inventory(List<InventoryRow> itens, BigDecimal valorTotal, BigDecimal valorParado,
                            int abaixoMinimo, BigDecimal coberturaMediaDias) {}

    // ---------- /me/pendencias ----------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreditoSugerido(String produto, BigDecimal valor) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Acao(String label, String rota, CreditoSugerido credito) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pendencia(String id, String tipo, String titulo, String descricao, BigDecimal valor,
                            LocalDate data, Acao acao) {}
}
