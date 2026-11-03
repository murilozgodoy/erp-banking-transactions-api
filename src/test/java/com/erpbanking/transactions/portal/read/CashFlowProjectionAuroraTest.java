package com.erpbanking.transactions.portal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.portal.read.CashFlowProjection.Conta;
import com.erpbanking.transactions.portal.read.CashFlowProjection.Recebivel;
import com.erpbanking.transactions.portal.read.CashFlowProjection.Venda;
import com.erpbanking.transactions.portal.read.MeReadDtos.FluxoCaixaDia;

/**
 * Fluxo da Aurora Pães (carga de demonstração, data-base 03/11/2026) com os dados do {@code aurora.sql}
 * (fixture {@code fixtures/aurora-fluxo-2026-11-03.csv}). O mock do front mostra o vale de 21/11 a 29/11 com
 * mínimo de −R$ 37.756,90 e recuperação até o fim da janela.
 */
class CashFlowProjectionAuroraTest {

    static final LocalDate HOJE = LocalDate.of(2026, 11, 3);
    static final double MINIMO_MOCK = -37_756.90;

    @Test
    void valeDeNovembroComRecuperacaoComoNoMock() throws Exception {
        List<FluxoCaixaDia> fluxo = projetarAurora();

        assertThat(fluxo).hasSize(60);
        FluxoCaixaDia minimo = fluxo.stream().min(Comparator.comparing(FluxoCaixaDia::saldoProjetado)).orElseThrow();
        assertThat(minimo.saldoProjetado().doubleValue()).isCloseTo(MINIMO_MOCK, within(Math.abs(MINIMO_MOCK) * 0.03));
        assertThat(minimo.data()).isBetween(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 27));

        List<LocalDate> negativos = fluxo.stream().filter(d -> d.saldoProjetado().signum() < 0)
                .map(FluxoCaixaDia::data).toList();
        assertThat(negativos).isNotEmpty().allSatisfy(d ->
                assertThat(d).isBetween(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 30)));
        assertThat(negativos).contains(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 29));
        // Um vale só: os dias negativos são consecutivos.
        assertThat(negativos.get(negativos.size() - 1)).isEqualTo(negativos.get(0).plusDays(negativos.size() - 1L));

        FluxoCaixaDia ultimo = fluxo.get(59);
        assertThat(ultimo.data()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(ultimo.saldoProjetado()).isGreaterThan(new BigDecimal("40000"));
        assertThat(fluxo).allSatisfy(d -> {
            assertThat(d.entradas().scale()).isEqualTo(2);
            assertThat(d.saidas().scale()).isEqualTo(2);
        });
    }

    static List<FluxoCaixaDia> projetarAurora() throws Exception {
        BigDecimal saldo = null;
        List<Venda> vendas = new ArrayList<>();
        List<Recebivel> recebiveis = new ArrayList<>();
        List<Conta> contas = new ArrayList<>();
        try (var in = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                CashFlowProjectionAuroraTest.class.getResourceAsStream("/fixtures/aurora-fluxo-2026-11-03.csv")),
                StandardCharsets.UTF_8))) {
            for (String linha = in.readLine(); linha != null; linha = in.readLine()) {
                if (linha.isBlank() || linha.startsWith("#")) {
                    continue;
                }
                String[] c = linha.split(";", -1);
                switch (c[0]) {
                    case "SALDO" -> saldo = new BigDecimal(c[1]);
                    case "V" -> vendas.add(new Venda(LocalDate.parse(c[1]), PaymentMethod.valueOf(c[2]),
                            Integer.parseInt(c[3]), new BigDecimal(c[4])));
                    case "R" -> recebiveis.add(new Recebivel(LocalDate.parse(c[1]), new BigDecimal(c[2]),
                            new BigDecimal(c[3]), ReceivableStatus.valueOf(c[4])));
                    case "P" -> contas.add(new Conta(LocalDate.parse(c[1]), null, PayableCategory.valueOf(c[2]),
                            PayableStatus.valueOf(c[3]), c[5], new BigDecimal(c[4])));
                    default -> throw new IllegalStateException("linha inválida: " + linha);
                }
            }
        }
        // O serviço passa só as vendas dos últimos 30 dias; a fixture tem 60 e a projeção filtra a janela.
        return CashFlowProjection.projetar(HOJE, PortalReadService.FLUXO_DIAS, saldo, vendas, recebiveis, contas);
    }
}
