package com.erpbanking.transactions.portal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.portal.read.PortalCalculations.GroupBy;

class PortalCalculationsTest {

    static final LocalDate HOJE = LocalDate.of(2026, 11, 3);

    @Test
    void numeroContaComDigitoModulo11() {
        assertThat(PortalCalculations.numeroConta(42)).isEqualTo("000042-6");
        assertThat(PortalCalculations.numeroConta(1)).isEqualTo("000001-9");
        // soma 2*1 = 2 → 11 - 2 = 9; id 6: 6*2=12 → 11-1=10 → 0
        assertThat(PortalCalculations.numeroConta(6)).isEqualTo("000006-0");
        assertThat(PortalCalculations.numeroConta(1234567)).hasSize(9);
    }

    @Test
    void statusEfetivoDeContaVencida() {
        Payable p = new Payable();
        p.setStatus(PayableStatus.ABERTO);
        p.setVencimento(HOJE.minusDays(1));
        assertThat(PortalCalculations.statusEfetivo(p, HOJE)).isEqualTo(PayableStatus.ATRASADO);
        p.setVencimento(HOJE);
        assertThat(PortalCalculations.statusEfetivo(p, HOJE)).isEqualTo(PayableStatus.ABERTO);
        p.setStatus(PayableStatus.AGENDADO);
        p.setVencimento(HOJE.minusDays(5));
        assertThat(PortalCalculations.statusEfetivo(p, HOJE)).isEqualTo(PayableStatus.AGENDADO);
        assertThat(PortalCalculations.emAberto(PayableStatus.PAGO)).isFalse();
    }

    @Test
    void encargosMultaMaisJurosProRata() {
        assertThat(PortalCalculations.encargosAtraso(new BigDecimal("1000"), 30)).isEqualByComparingTo("30.00");
        assertThat(PortalCalculations.encargosAtraso(new BigDecimal("1000"), 15)).isEqualByComparingTo("25.00");
        assertThat(PortalCalculations.encargosAtraso(new BigDecimal("1000"), 0)).isEqualByComparingTo("0");
    }

    @Test
    void percentuaisEMedias() {
        assertThat(PortalCalculations.pct(new BigDecimal("1"), new BigDecimal("3"))).isEqualByComparingTo("33.3");
        assertThat(PortalCalculations.pct(BigDecimal.ONE, BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(PortalCalculations.pct(BigDecimal.ONE, null)).isEqualByComparingTo("0");
        assertThat(PortalCalculations.variacaoPct(new BigDecimal("90"), new BigDecimal("100"))).isEqualByComparingTo("-10.0");
        assertThat(PortalCalculations.variacaoPct(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThat(PortalCalculations.variacaoPct(BigDecimal.TEN, null)).isNull();
        assertThat(PortalCalculations.media(new BigDecimal("10"), 3)).isEqualByComparingTo("3.33");
        assertThat(PortalCalculations.media(BigDecimal.TEN, 0)).isEqualByComparingTo("0");
        assertThat(PortalCalculations.money(null)).isEqualByComparingTo("0");
    }

    @Test
    void formasDeCartao() {
        assertThat(PortalCalculations.pagamentoCartao(PaymentMethod.CARTAO_CREDITO)).isTrue();
        assertThat(PortalCalculations.pagamentoCartao(PaymentMethod.CARTAO_DEBITO)).isTrue();
        assertThat(PortalCalculations.pagamentoCartao(PaymentMethod.PIX)).isFalse();
    }

    @Test
    void groupByPeriodos() {
        assertThat(GroupBy.parse(null)).isEqualTo(GroupBy.DAY);
        assertThat(GroupBy.parse(" ")).isEqualTo(GroupBy.DAY);
        assertThat(GroupBy.parse("Week")).isEqualTo(GroupBy.WEEK);
        assertThatThrownBy(() -> GroupBy.parse("ano")).hasMessageContaining("group_by");
        assertThat(GroupBy.WEEK.inicio(HOJE)).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(GroupBy.MONTH.inicio(HOJE)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(GroupBy.MONTH.rotulo(LocalDate.of(2026, 11, 1))).isEqualTo("2026-11");
        assertThat(GroupBy.DAY.rotulo(HOJE)).isEqualTo("2026-11-03");
        assertThat(GroupBy.WEEK.proximo(LocalDate.of(2026, 11, 2))).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(GroupBy.MONTH.proximo(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void formatosBrasileiros() {
        assertThat(PortalCalculations.brl(new BigDecimal("1234.5"))).isEqualTo("R$ 1.234,50");
        assertThat(PortalCalculations.dataBr(HOJE)).isEqualTo("03/11/2026");
        assertThat(PortalCalculations.diasEntre(HOJE.minusDays(4), HOJE)).isEqualTo(4);
    }
}
