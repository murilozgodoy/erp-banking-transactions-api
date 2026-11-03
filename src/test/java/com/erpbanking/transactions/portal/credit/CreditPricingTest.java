package com.erpbanking.transactions.portal.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.erpbanking.transactions.portal.credit.CreditPricing.Aprovacao;
import com.erpbanking.transactions.portal.credit.CreditPricing.Parcela;
import com.erpbanking.transactions.portal.credit.CreditPricing.Quote;

class CreditPricingTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 3);

    @Test
    void taxaPorFaixaEProduto() {
        assertThat(CreditPricing.taxaMensal(CreditProduct.CAPITAL_DE_GIRO, "BAIXO")).isEqualByComparingTo("1.69");
        assertThat(CreditPricing.taxaMensal(CreditProduct.CAPITAL_DE_GIRO, "MEDIO")).isEqualByComparingTo("2.49");
        assertThat(CreditPricing.taxaMensal(CreditProduct.CAPITAL_DE_GIRO, "ALTO")).isEqualByComparingTo("3.49");
        assertThat(CreditPricing.taxaMensal(CreditProduct.ANTECIPACAO_RECEBIVEIS, "MEDIO")).isEqualByComparingTo("2.09");
        assertThat(CreditPricing.taxaMensal(CreditProduct.FINANCIAMENTO_ESTOQUE, "BAIXO")).isEqualByComparingTo("1.49");
        assertThat(CreditPricing.taxaMensal(CreditProduct.ALAVANCAGEM, "ALTO")).isEqualByComparingTo("3.79");
        assertThat(CreditPricing.taxaMensal(CreditProduct.CONSOLIDACAO_DIVIDAS, "MEDIO")).isEqualByComparingTo("2.49");
        // Sem análise: conservador (ALTO).
        assertThat(CreditPricing.taxaMensal(CreditProduct.CAPITAL_DE_GIRO, null)).isEqualByComparingTo("3.49");
        assertThat(CreditPricing.taxaMensal(CreditProduct.CAPITAL_DE_GIRO, null).scale()).isEqualTo(4);
        assertThat(CreditPricing.negada("MUITO_ALTO")).isTrue();
        assertThat(CreditPricing.negada("ALTO")).isFalse();
    }

    @Test
    void limitesPorProduto() {
        BigDecimal rec = new BigDecimal("1000.00");
        BigDecimal est = new BigDecimal("500.00");
        BigDecimal receita = new BigDecimal("2000.00");
        assertThat(CreditPricing.limite(CreditProduct.ANTECIPACAO_RECEBIVEIS, rec, est, receita)).isEqualByComparingTo("800.00");
        assertThat(CreditPricing.limite(CreditProduct.FINANCIAMENTO_ESTOQUE, rec, est, receita)).isEqualByComparingTo("300.00");
        assertThat(CreditPricing.limite(CreditProduct.CAPITAL_DE_GIRO, rec, est, receita)).isEqualByComparingTo("2000.00");
        assertThat(CreditPricing.limite(CreditProduct.CONSOLIDACAO_DIVIDAS, rec, est, receita)).isEqualByComparingTo("2000.00");
        assertThat(CreditPricing.limite(CreditProduct.ALAVANCAGEM, rec, est, receita)).isEqualByComparingTo("6000.00");
        assertThat(CreditPricing.limite(CreditProduct.ALAVANCAGEM, rec, est, new BigDecimal("-1"))).isEqualByComparingTo("0");
    }

    @Test
    void aprovacao() {
        BigDecimal lim = new BigDecimal("1000");
        assertThat(CreditPricing.aprovacao("MUITO_ALTO", true, BigDecimal.ONE, lim)).isEqualTo(Aprovacao.NEGADO);
        assertThat(CreditPricing.aprovacao("BAIXO", true, lim, lim)).isEqualTo(Aprovacao.PRE_APROVADO);
        assertThat(CreditPricing.aprovacao("MEDIO", true, BigDecimal.ONE, lim)).isEqualTo(Aprovacao.PRE_APROVADO);
        assertThat(CreditPricing.aprovacao("MEDIO", true, new BigDecimal("1000.01"), lim)).isEqualTo(Aprovacao.ANALISE);
        assertThat(CreditPricing.aprovacao("ALTO", true, BigDecimal.ONE, lim)).isEqualTo(Aprovacao.ANALISE);
        assertThat(CreditPricing.aprovacao("BAIXO", false, BigDecimal.ONE, lim)).isEqualTo(Aprovacao.ANALISE);
        assertThat(CreditPricing.aprovacao(null, false, BigDecimal.ONE, lim)).isEqualTo(Aprovacao.ANALISE);
    }

    @Test
    void tabelaPriceFechaOPrincipalComParcelasIguais() {
        Quote q = CreditPricing.quote(new BigDecimal("10000"), 12, new BigDecimal("2.49"), HOJE);
        assertThat(q.parcelas()).hasSize(12);
        BigDecimal amortTotal = q.parcelas().stream().map(Parcela::amortizacao).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal jurosTotal = q.parcelas().stream().map(Parcela::juros).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(amortTotal).isEqualByComparingTo("10000.00");
        assertThat(q.totalPago()).isEqualByComparingTo(amortTotal.add(jurosTotal));
        // PMT = 10000 * i / (1 - (1+i)^-12) = 974.28 (a última pode diferir por centavos de arredondamento)
        for (Parcela p : q.parcelas().subList(0, 11)) {
            assertThat(p.valor()).isEqualByComparingTo("974.28");
            assertThat(p.amortizacao().add(p.juros())).isEqualByComparingTo(p.valor());
        }
        assertThat(q.parcelas().get(11).valor().subtract(new BigDecimal("974.28")).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.05"));
        assertThat(q.parcelas().get(0).juros()).isEqualByComparingTo("249.00");
        assertThat(q.parcelas().get(0).vencimento()).isEqualTo(HOJE.plusMonths(1));
        assertThat(q.parcelas().get(0).n()).isEqualTo(1);
        // Amortização cresce na Price.
        assertThat(q.parcelas().get(11).amortizacao()).isGreaterThan(q.parcelas().get(0).amortizacao());
    }

    @Test
    void iofFixoMaisDiarioPorAmortizacaoLimitadoA365Dias() {
        Quote q = CreditPricing.quote(new BigDecimal("10000"), 24, new BigDecimal("1.69"), HOJE);
        BigDecimal esperadoCap = new BigDecimal("38.00");
        BigDecimal esperadoSemCap = new BigDecimal("38.00");
        for (Parcela p : q.parcelas()) {
            long dias = ChronoUnit.DAYS.between(HOJE, p.vencimento());
            esperadoCap = esperadoCap.add(p.amortizacao().multiply(new BigDecimal("0.000082"))
                    .multiply(BigDecimal.valueOf(Math.min(dias, 365))));
            esperadoSemCap = esperadoSemCap.add(p.amortizacao().multiply(new BigDecimal("0.000082"))
                    .multiply(BigDecimal.valueOf(dias)));
        }
        assertThat(q.iof()).isEqualByComparingTo(CreditPricing.money(esperadoCap));
        assertThat(q.iof()).isLessThan(CreditPricing.money(esperadoSemCap));
        assertThat(q.valorLiquido()).isEqualByComparingTo(new BigDecimal("10000").subtract(q.iof()));
        assertThat(q.iof().scale()).isEqualTo(2);
    }

    @Test
    void cetIncluiIofEFicaAcimaDaTaxaAnualizada() {
        Quote q = CreditPricing.quote(new BigDecimal("50000"), 12, new BigDecimal("2.49"), HOJE);
        double taxaAnual = (Math.pow(1.0249, 12) - 1) * 100;
        assertThat(q.cetAnual().doubleValue()).isGreaterThan(taxaAnual);
        assertThat(q.cetAnual().scale()).isEqualTo(4);
        // Sem IOF (fluxo = parcelas sobre o principal), a TIR seria a própria taxa.
        BigDecimal semIof = CreditPricing.cetAnual(new BigDecimal("50000"), q.parcelas());
        assertThat(semIof.doubleValue()).isCloseTo(taxaAnual, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void prazoDeUmMesETaxaZero() {
        Quote q = CreditPricing.quote(new BigDecimal("1000"), 1, new BigDecimal("1.29"), HOJE);
        assertThat(q.parcelas()).hasSize(1);
        assertThat(q.parcelas().get(0).valor()).isEqualByComparingTo("1012.90");
        assertThat(CreditPricing.pmt(new BigDecimal("1200"), BigDecimal.ZERO, 12)).isEqualByComparingTo("100.00");
        assertThatThrownBy(() -> CreditPricing.quote(BigDecimal.TEN, 0, BigDecimal.ONE, HOJE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void produtosOfertadosEPrazos() {
        assertThat(CreditProduct.ANTECIPACAO_RECEBIVEIS.prazoMaxMeses()).isEqualTo(6);
        assertThat(CreditProduct.CAPITAL_DE_GIRO.prazoMaxMeses()).isEqualTo(24);
        assertThat(CreditProduct.FINANCIAMENTO_ESTOQUE.prazoMaxMeses()).isEqualTo(18);
        assertThat(CreditProduct.ALAVANCAGEM.prazoMaxMeses()).isEqualTo(36);
        assertThat(CreditProduct.CONSOLIDACAO_DIVIDAS.ofertado()).isFalse();
    }
}
