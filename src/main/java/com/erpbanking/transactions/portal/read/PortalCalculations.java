package com.erpbanking.transactions.portal.read;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.PaymentMethod;

/** Cálculos puros do portal (sem banco), para teste unitário direto. */
public final class PortalCalculations {

    /** Fuso do negócio: "hoje" e datas de lançamentos são calculados em São Paulo. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public static final String AGENCIA = "0001";
    /** Multa por atraso (2%) e juros de mora (1% ao mês, pro rata die). */
    static final BigDecimal MULTA = new BigDecimal("0.02");
    static final BigDecimal JUROS_MES = new BigDecimal("0.01");

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private PortalCalculations() {}

    public static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Número da conta: id com 6 dígitos + "-" + dígito verificador módulo 11 (pesos 2..9 da direita para a
     * esquerda; resto que daria 10 ou 11 vira 0). Ex.: id 42 → "000042-6".
     */
    public static String numeroConta(long id) {
        String base = String.format("%06d", id);
        int soma = 0;
        int peso = 2;
        for (int i = base.length() - 1; i >= 0; i--) {
            soma += (base.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int dv = 11 - (soma % 11);
        return base + "-" + (dv >= 10 ? 0 : dv);
    }

    /** Status efetivo: ABERTO vencido (antes de hoje) é mostrado como ATRASADO. */
    public static PayableStatus statusEfetivo(Payable p, LocalDate hoje) {
        if (p.getStatus() == PayableStatus.ABERTO && p.getVencimento().isBefore(hoje)) {
            return PayableStatus.ATRASADO;
        }
        return p.getStatus();
    }

    public static boolean emAberto(PayableStatus s) {
        return s != PayableStatus.PAGO;
    }

    /** Encargos estimados de uma conta vencida: 2% de multa + 1% ao mês pro rata pelos dias de atraso. */
    public static BigDecimal encargosAtraso(BigDecimal valor, long diasAtraso) {
        if (diasAtraso <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal juros = valor.multiply(JUROS_MES).multiply(BigDecimal.valueOf(diasAtraso))
                .divide(BigDecimal.valueOf(30), 10, RoundingMode.HALF_UP);
        return money(valor.multiply(MULTA).add(juros));
    }

    /** Percentual (1 casa) de parte sobre total; total zero → 0. */
    public static BigDecimal pct(BigDecimal parte, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return parte.multiply(CEM).divide(total, 1, RoundingMode.HALF_UP);
    }

    /** Variação percentual (1 casa) de atual sobre anterior; sem base (anterior zero) → null. */
    public static BigDecimal variacaoPct(BigDecimal atual, BigDecimal anterior) {
        if (anterior == null || anterior.signum() == 0) {
            return null;
        }
        return atual.subtract(anterior).multiply(CEM).divide(anterior, 1, RoundingMode.HALF_UP);
    }

    public static BigDecimal media(BigDecimal total, long n) {
        return n == 0 ? BigDecimal.ZERO.setScale(2) : total.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    public static boolean pagamentoCartao(PaymentMethod forma) {
        return forma == PaymentMethod.CARTAO_CREDITO || forma == PaymentMethod.CARTAO_DEBITO;
    }

    /** Agrupamento da série de vendas. */
    public enum GroupBy {
        DAY, WEEK, MONTH;

        public static GroupBy parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DAY;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("group_by deve ser day, week ou month");
            }
        }

        /** Início do período que contém a data (semana começa na segunda). */
        public LocalDate inicio(LocalDate d) {
            return switch (this) {
                case DAY -> d;
                case WEEK -> segunda(d);
                case MONTH -> d.withDayOfMonth(1);
            };
        }

        public LocalDate proximo(LocalDate inicio) {
            return switch (this) {
                case DAY -> inicio.plusDays(1);
                case WEEK -> inicio.plusWeeks(1);
                case MONTH -> inicio.plusMonths(1);
            };
        }

        /** Rótulo ISO: dia e semana = yyyy-MM-dd (segunda-feira), mês = yyyy-MM. */
        public String rotulo(LocalDate inicio) {
            return this == MONTH ? inicio.toString().substring(0, 7) : inicio.toString();
        }
    }

    public static LocalDate segunda(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static long diasEntre(LocalDate de, LocalDate ate) {
        return ChronoUnit.DAYS.between(de, ate);
    }

    public static String dataBr(LocalDate d) {
        return d.format(BR_DATE);
    }

    /** Valor em reais no padrão brasileiro: "R$ 1.234,56". */
    public static String brl(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + f.format(money(v));
    }
}
