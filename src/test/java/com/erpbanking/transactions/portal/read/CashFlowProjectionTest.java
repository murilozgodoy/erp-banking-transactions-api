package com.erpbanking.transactions.portal.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.portal.read.CashFlowProjection.Conta;
import com.erpbanking.transactions.portal.read.CashFlowProjection.Recebivel;
import com.erpbanking.transactions.portal.read.CashFlowProjection.Venda;
import com.erpbanking.transactions.portal.read.MeReadDtos.FluxoCaixaDia;

class CashFlowProjectionTest {

    /** Terça-feira. Janela de vendas: 05/10 (segunda) a 03/11: segundas e terças aparecem 5 vezes, os demais 4. */
    static final LocalDate HOJE = LocalDate.of(2026, 11, 3);

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    static Conta conta(String venc, PayableCategory cat, PayableStatus status, String fornecedor, String valor) {
        return new Conta(LocalDate.parse(venc), null, cat, status, fornecedor, bd(valor));
    }

    @Test
    void recebiveisEContasPelaDataComVencidosNoDiaZero() {
        var recebiveis = List.of(
                new Recebivel(HOJE.minusDays(2), bd("10"), bd("10"), ReceivableStatus.PREVISTO),
                new Recebivel(HOJE.plusDays(1), bd("52"), bd("50"), ReceivableStatus.PREVISTO),
                new Recebivel(HOJE.plusDays(1), bd("99"), bd("99"), ReceivableStatus.RECEBIDO),
                new Recebivel(HOJE.plusDays(3), bd("70"), bd("70"), ReceivableStatus.PREVISTO));
        var contas = List.of(
                new Conta(HOJE.minusDays(1), null, PayableCategory.FORNECEDOR, PayableStatus.ATRASADO, "A", bd("30")),
                new Conta(HOJE.plusDays(1), HOJE.plusDays(2), PayableCategory.SERVICOS, PayableStatus.AGENDADO, "B",
                        bd("200")),
                new Conta(HOJE.plusDays(2), null, PayableCategory.FORNECEDOR, PayableStatus.PAGO, "C", bd("999")),
                new Conta(HOJE.plusDays(9), null, PayableCategory.FORNECEDOR, PayableStatus.ABERTO, "D", bd("1")));

        List<FluxoCaixaDia> fluxo = CashFlowProjection.projetar(HOJE, 3, bd("100"), List.of(), recebiveis, contas);

        assertThat(fluxo).extracting(FluxoCaixaDia::data).containsExactly(HOJE, HOJE.plusDays(1), HOJE.plusDays(2));
        assertThat(fluxo.get(0).entradas()).isEqualByComparingTo("10");
        assertThat(fluxo.get(0).saidas()).isEqualByComparingTo("30");
        assertThat(fluxo.get(0).saldoProjetado()).isEqualByComparingTo("80");
        assertThat(fluxo.get(1).entradas()).isEqualByComparingTo("50");
        assertThat(fluxo.get(1).saidas()).isEqualByComparingTo("0");
        assertThat(fluxo.get(2).saidas()).isEqualByComparingTo("200");
        assertThat(fluxo.get(2).saldoProjetado()).isEqualByComparingTo("-70");
        assertThat(fluxo.get(2).entradas().scale()).isEqualTo(2);
    }

    @Test
    void vendasEstimadasPorDiaDaSemanaEFormaComPrazosDeLiquidacao() {
        var vendas = List.of(
                new Venda(LocalDate.of(2026, 11, 3), PaymentMethod.PIX, 1, bd("100")),            // ter: 100/5
                new Venda(LocalDate.of(2026, 10, 28), PaymentMethod.DINHEIRO, 1, bd("40")),       // qua: 40/4
                new Venda(LocalDate.of(2026, 10, 29), PaymentMethod.CARTAO_DEBITO, 1, bd("400")), // qui: 100 → sex
                new Venda(LocalDate.of(2026, 10, 30), PaymentMethod.CARTAO_CREDITO, 3, bd("900")),// sex: 225 em 3x
                new Venda(LocalDate.of(2026, 10, 31), PaymentMethod.CARTAO_CREDITO, 1, bd("200")),// sáb: 50 → D+30
                new Venda(LocalDate.of(2026, 10, 4), PaymentMethod.PIX, 1, bd("7000")));         // fora da janela
        // Líquido/bruto observado: 0,98.
        var recebiveis = List.of(new Recebivel(HOJE.plusDays(200), bd("100"), bd("98"), ReceivableStatus.PREVISTO));

        List<FluxoCaixaDia> fluxo = CashFlowProjection.projetar(HOJE, 60, BigDecimal.ZERO, vendas, recebiveis,
                List.of());

        assertThat(fluxo.get(0).entradas()).isEqualByComparingTo("20");   // ter 03/11: PIX D+0
        assertThat(fluxo.get(1).entradas()).isEqualByComparingTo("10");   // qua 04/11: dinheiro D+0
        assertThat(fluxo.get(2).entradas()).isEqualByComparingTo("0");    // qui 05/11: o débito só entra D+1
        assertThat(fluxo.get(3).entradas()).isEqualByComparingTo("98");   // sex 06/11: débito de qui × 0,98
        assertThat(fluxo.get(4).entradas()).isEqualByComparingTo("0");    // crédito só a partir de D+30
        assertThat(fluxo.get(7).entradas()).isEqualByComparingTo("20");
        assertThat(fluxo.get(31).entradas()).isEqualByComparingTo("98");  // sex 04/12: só o débito de qui
        // 1ª parcela (225 × 0,98 / 3) da venda estimada de sex 06/11 (dia 3) entra no dia 33; a 2ª (dia 63)
        // fica fora da janela. Crédito à vista (50 × 0,98) do sáb 07/11 (dia 4) entra no dia 34.
        assertThat(fluxo.get(33).entradas()).isEqualByComparingTo("73.50");
        assertThat(fluxo.get(34).entradas()).isEqualByComparingTo("49");
        assertThat(fluxo.get(40).entradas()).isEqualByComparingTo("73.50");
        assertThat(fluxo.get(41).entradas()).isEqualByComparingTo("49");
    }

    @Test
    void liquidoSobreBrutoUsaAMedianaOuOPadrao() {
        assertThat(CashFlowProjection.liquidoSobreBruto(List.of())).isEqualByComparingTo("0.97");
        var impar = List.of(new Recebivel(HOJE, bd("100"), bd("96"), ReceivableStatus.PREVISTO),
                new Recebivel(HOJE, bd("100"), bd("97"), ReceivableStatus.RECEBIDO),
                new Recebivel(HOJE, bd("46800"), bd("46796.51"), ReceivableStatus.PREVISTO),
                new Recebivel(HOJE, BigDecimal.ZERO, bd("5"), ReceivableStatus.PREVISTO),
                new Recebivel(HOJE, null, bd("5"), ReceivableStatus.PREVISTO),
                new Recebivel(HOJE, bd("5"), null, ReceivableStatus.PREVISTO));
        assertThat(CashFlowProjection.liquidoSobreBruto(impar)).isEqualByComparingTo("0.97");
        var par = List.of(new Recebivel(HOJE, bd("100"), bd("96"), ReceivableStatus.PREVISTO),
                new Recebivel(HOJE, bd("100"), bd("98"), ReceivableStatus.PREVISTO));
        assertThat(CashFlowProjection.liquidoSobreBruto(par)).isEqualByComparingTo("0.97");
    }

    @Test
    void despesasRecorrentesSoNosMesesSemContaLancada() {
        var contas = List.of(
                // Salários: pago em 05/10 e pago antecipado o de 05/11 (mesmo fornecedor no mês) → só 05/12.
                conta("2026-10-05", PayableCategory.FOLHA, PayableStatus.PAGO, "Salários", "1000"),
                conta("2026-11-05", PayableCategory.FOLHA, PayableStatus.PAGO, "Salários", "1000"),
                // Aluguel: novembro já tem conta aberta da categoria → só 10/12.
                conta("2026-10-10", PayableCategory.ALUGUEL, PayableStatus.PAGO, "Imobiliária", "500"),
                conta("2026-11-10", PayableCategory.ALUGUEL, PayableStatus.ABERTO, "Imobiliária", "500"),
                // Empréstimo: o pago mais recente (31/10) é o modelo; dia 31 vira 30/11 e 31/12.
                conta("2026-09-30", PayableCategory.EMPRESTIMO, PayableStatus.PAGO, "Banco", "250"),
                conta("2026-10-31", PayableCategory.EMPRESTIMO, PayableStatus.PAGO, "Banco", "300"),
                // Imposto pago há mais de 60 dias e fornecedor comum não se repetem.
                conta("2026-08-20", PayableCategory.IMPOSTO, PayableStatus.PAGO, "DAS", "700"),
                conta("2026-10-15", PayableCategory.FORNECEDOR, PayableStatus.PAGO, "Moinho", "800"),
                // Modelo de 02/10: em novembro o dia 02 já passou; dezembro tem conta aberta da categoria.
                conta("2026-10-02", PayableCategory.IMPOSTO, PayableStatus.PAGO, "FGTS", "90"),
                conta("2026-12-02", PayableCategory.IMPOSTO, PayableStatus.ABERTO, "FGTS", "90"));

        List<FluxoCaixaDia> fluxo = CashFlowProjection.projetar(HOJE, 60, BigDecimal.ZERO, List.of(), List.of(),
                contas);

        assertThat(saida(fluxo, "2026-11-05")).isEqualByComparingTo("0");
        assertThat(saida(fluxo, "2026-12-05")).isEqualByComparingTo("1000");
        assertThat(saida(fluxo, "2026-11-10")).isEqualByComparingTo("500");   // a conta aberta, não a estimada
        assertThat(saida(fluxo, "2026-12-10")).isEqualByComparingTo("500");
        assertThat(saida(fluxo, "2026-11-30")).isEqualByComparingTo("300");
        assertThat(saida(fluxo, "2026-12-31")).isEqualByComparingTo("300");
        assertThat(saida(fluxo, "2026-11-20")).isEqualByComparingTo("0");
        assertThat(saida(fluxo, "2026-11-15")).isEqualByComparingTo("0");
        assertThat(saida(fluxo, "2026-12-02")).isEqualByComparingTo("90");
        assertThat(fluxo.get(59).saldoProjetado()).isEqualByComparingTo("-2690");
    }

    static BigDecimal saida(List<FluxoCaixaDia> fluxo, String data) {
        return fluxo.stream().filter(d -> d.data().equals(LocalDate.parse(data))).findFirst().orElseThrow().saidas();
    }
}
