package com.erpbanking.transactions.portal.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Precificação do crédito do portal (PORTAL_CLIENTE.md, seção 5), pura e sem estado.
 * <p>Convenção: taxas em <b>percentual</b> com escala 4 ({@code 2.4900} = 2,49% a.m.), como o
 * {@code taxa_mensal} das oportunidades do Score; valores em BRL com escala 2 (HALF_EVEN).
 * <ul>
 *   <li>Taxa base por faixa: BAIXO 1,69 · MEDIO 2,49 · ALTO 3,49 · MUITO_ALTO nega. Sem análise usa a de ALTO.</li>
 *   <li>Ajuste do produto em pp ({@link CreditProduct#ajustePp()}).</li>
 *   <li>Limites: antecipação 80% dos recebíveis; estoque 60% do valor de estoque; capital de giro e
 *       consolidação 1× a receita de 30 dias; alavancagem 3× a receita de 30 dias.</li>
 *   <li>Tabela Price; IOF = 0,38% do valor + 0,0082% ao dia sobre cada amortização (dias até o
 *       vencimento, no máximo 365); valor líquido = valor − IOF.</li>
 *   <li>CET anual = (1 + i)^12 − 1, com i a TIR mensal de (valor líquido recebido, parcelas pagas).</li>
 * </ul>
 */
public final class CreditPricing {

    public static final String BAIXO = "BAIXO";
    public static final String MEDIO = "MEDIO";
    public static final String ALTO = "ALTO";
    public static final String MUITO_ALTO = "MUITO_ALTO";

    static final BigDecimal TAXA_BAIXO = new BigDecimal("1.69");
    static final BigDecimal TAXA_MEDIO = new BigDecimal("2.49");
    static final BigDecimal TAXA_ALTO = new BigDecimal("3.49");

    static final BigDecimal IOF_FIXO = new BigDecimal("0.0038");
    static final BigDecimal IOF_DIARIO = new BigDecimal("0.000082");
    static final int IOF_MAX_DIAS = 365;

    static final BigDecimal PCT_ANTECIPACAO = new BigDecimal("0.80");
    static final BigDecimal PCT_ESTOQUE = new BigDecimal("0.60");
    static final BigDecimal MULT_ALAVANCAGEM = new BigDecimal("3");

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CreditPricing() {}

    public enum Aprovacao { PRE_APROVADO, ANALISE, NEGADO }

    public record Parcela(int n, LocalDate vencimento, BigDecimal amortizacao, BigDecimal juros, BigDecimal valor) {}

    public record Quote(List<Parcela> parcelas, BigDecimal iof, BigDecimal valorLiquido, BigDecimal totalPago,
                        BigDecimal cetAnual) {}

    /** Faixa que nega crédito. */
    public static boolean negada(String faixa) {
        return MUITO_ALTO.equals(faixa);
    }

    /** Taxa base (% a.m.) da faixa; faixa desconhecida ou ausente usa a de ALTO (conservador). */
    public static BigDecimal taxaBase(String faixa) {
        if (BAIXO.equals(faixa)) {
            return TAXA_BAIXO;
        }
        if (MEDIO.equals(faixa)) {
            return TAXA_MEDIO;
        }
        return TAXA_ALTO;
    }

    /** Taxa mensal (%) do produto para a faixa, escala 4. */
    public static BigDecimal taxaMensal(CreditProduct produto, String faixa) {
        return taxaBase(faixa).add(produto.ajustePp()).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Limite do produto.
     *
     * @param recebiveis   soma do valor líquido dos recebíveis considerados (antecipação)
     * @param estoque      Σ quantidade × custo unitário
     * @param receita30d   receita das vendas dos últimos 30 dias
     */
    public static BigDecimal limite(CreditProduct produto, BigDecimal recebiveis, BigDecimal estoque,
                                    BigDecimal receita30d) {
        BigDecimal limite = switch (produto) {
            case ANTECIPACAO_RECEBIVEIS -> recebiveis.multiply(PCT_ANTECIPACAO);
            case FINANCIAMENTO_ESTOQUE -> estoque.multiply(PCT_ESTOQUE);
            case ALAVANCAGEM -> receita30d.multiply(MULT_ALAVANCAGEM);
            case CAPITAL_DE_GIRO, CONSOLIDACAO_DIVIDAS -> receita30d;
        };
        return money(limite.max(BigDecimal.ZERO));
    }

    /**
     * Aprovação: NEGADO para MUITO_ALTO; PRE_APROVADO com análise concluída, faixa BAIXO/MEDIO e valor
     * dentro do limite; ANALISE no resto (ALTO, acima do limite, sem análise ou análise pendente).
     */
    public static Aprovacao aprovacao(String faixa, boolean analiseConcluida, BigDecimal valor, BigDecimal limite) {
        if (negada(faixa)) {
            return Aprovacao.NEGADO;
        }
        boolean faixaBoa = BAIXO.equals(faixa) || MEDIO.equals(faixa);
        if (analiseConcluida && faixaBoa && valor.compareTo(limite) <= 0) {
            return Aprovacao.PRE_APROVADO;
        }
        return Aprovacao.ANALISE;
    }

    /** Tabela Price, IOF e CET para o valor, prazo e taxa (% a.m.), com vencimentos mensais a partir de hoje. */
    public static Quote quote(BigDecimal valor, int prazoMeses, BigDecimal taxaMensalPct, LocalDate hoje) {
        if (prazoMeses < 1) {
            throw new IllegalArgumentException("prazo_meses deve ser >= 1");
        }
        BigDecimal principal = money(valor);
        BigDecimal i = taxaMensalPct.divide(HUNDRED, MC);
        BigDecimal pmt = pmt(principal, i, prazoMeses);

        List<Parcela> parcelas = new ArrayList<>(prazoMeses);
        BigDecimal saldo = principal;
        BigDecimal iofDiario = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (int n = 1; n <= prazoMeses; n++) {
            LocalDate vencimento = hoje.plusMonths(n);
            BigDecimal juros = money(saldo.multiply(i, MC));
            BigDecimal amortizacao = n == prazoMeses ? saldo : pmt.subtract(juros);
            BigDecimal parcela = amortizacao.add(juros);
            saldo = saldo.subtract(amortizacao);
            long dias = Math.min(ChronoUnit.DAYS.between(hoje, vencimento), IOF_MAX_DIAS);
            iofDiario = iofDiario.add(amortizacao.multiply(IOF_DIARIO).multiply(BigDecimal.valueOf(dias)));
            total = total.add(parcela);
            parcelas.add(new Parcela(n, vencimento, amortizacao, juros, parcela));
        }
        BigDecimal iof = money(principal.multiply(IOF_FIXO).add(iofDiario));
        BigDecimal liquido = principal.subtract(iof);
        BigDecimal cet = cetAnual(liquido, parcelas);
        return new Quote(List.copyOf(parcelas), iof, liquido, total, cet);
    }

    static BigDecimal pmt(BigDecimal principal, BigDecimal i, int n) {
        if (i.signum() == 0) {
            return money(principal.divide(BigDecimal.valueOf(n), MC));
        }
        BigDecimal f = BigDecimal.ONE.add(i).pow(n, MC);
        return money(principal.multiply(i, MC).multiply(f, MC).divide(f.subtract(BigDecimal.ONE), MC));
    }

    /** CET anual (%) pela TIR mensal dos fluxos (bisseção), escala 4. */
    static BigDecimal cetAnual(BigDecimal liquido, List<Parcela> parcelas) {
        double pv = liquido.doubleValue();
        double lo = 0.0;
        double hi = 1.0;
        for (int it = 0; it < 200; it++) {
            double mid = (lo + hi) / 2;
            if (npv(mid, parcelas) > pv) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double irr = (lo + hi) / 2;
        double anual = Math.pow(1 + irr, 12) - 1;
        return BigDecimal.valueOf(anual * 100).setScale(4, RoundingMode.HALF_UP);
    }

    private static double npv(double rate, List<Parcela> parcelas) {
        double sum = 0;
        for (Parcela p : parcelas) {
            sum += p.valor().doubleValue() / Math.pow(1 + rate, p.n());
        }
        return sum;
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }
}
