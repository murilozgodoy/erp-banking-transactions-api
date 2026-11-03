package com.erpbanking.transactions.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.portal.Permissoes;

/** Entradas hostis em /me: valores, limites do membro, simulação, recebíveis e agendamento. Sempre 4xx, nunca 500. */
class InputValidationMvcTest extends SecurityTestSupport {

    private static final List<String> VALORES_INVALIDOS = List.of("-1", "0", "0.00", "\"NaN\"", "NaN", "\"Infinity\"",
            "Infinity", "-Infinity", "1e400", "1E+400", "1e-400", "123456789012345678", "1.001", "\"abc\"", "null",
            "1" + "0".repeat(2000));

    @Test
    void valorDoPagamentoInvalidoDa400SemEfeito() throws Exception {
        String dono = dono(empresaA);
        Account conta = conta(empresaA, "1000.00");
        for (String valor : VALORES_INVALIDOS) {
            postAs(dono, "/me/payments", "{\"tipo\":\"PIX\",\"valor\":" + valor + ",\"destinatario\":\"X\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("validation_failed"));
        }
        assertThat(pagamentosDa(empresaA)).isZero();
        assertThat(saldo(conta)).isEqualByComparingTo("1000.00");
    }

    @Test
    void valorDaSimulacaoEPrazoForaDosLimitesDa400() throws Exception {
        String dono = dono(empresaA);
        for (String valor : VALORES_INVALIDOS) {
            postAs(dono, "/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":" + valor
                    + ",\"prazo_meses\":6}").andExpect(status().isBadRequest());
            postAs(dono, "/me/credit/requests", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":" + valor
                    + ",\"prazo_meses\":6}").andExpect(status().isBadRequest());
        }
        for (String prazo : List.of("0", "-1", "1000", "2147483647", "99999999999", "\"x\"")) {
            postAs(dono, "/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":"
                    + prazo + "}").andExpect(status().isBadRequest());
        }
        assertThat(simulacoesDa(empresaA)).isZero();
        assertThat(pedidosDa(empresaA)).isZero();
        // Maior valor aceito (17 dígitos inteiros): 201, sem estourar a coluna nem o cálculo.
        postAs(dono, "/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":99999999999999999.99,"
                + "\"prazo_meses\":6}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.aprovacao").value("ANALISE"));
    }

    @Test
    void receivableIdsAcimaDoMaximoDa400() throws Exception {
        String dono = dono(empresaA);
        String ids = LongStream.rangeClosed(1, 201).mapToObj(Long::toString).collect(Collectors.joining(","));
        postAs(dono, "/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":100,"
                + "\"prazo_meses\":3,\"receivable_ids\":[" + ids + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.receivableIds").exists());
        assertThat(simulacoesDa(empresaA)).isZero();
    }

    @Test
    void agendamentoMuitoNoFuturoDa400() throws Exception {
        String dono = dono(empresaA);
        conta(empresaA, "1000.00");
        LocalDate hoje = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
        for (String data : List.of(hoje.plusDays(366).toString(), "9999-12-31", "+999999999-12-31")) {
            postAs(dono, "/me/payments", "{\"tipo\":\"PIX\",\"valor\":10,\"destinatario\":\"X\",\"agendado_para\":\""
                    + data + "\"}").andExpect(status().isBadRequest());
        }
        assertThat(pagamentosDa(empresaA)).isZero();
        postAs(dono, "/me/payments", "{\"tipo\":\"PIX\",\"valor\":10,\"destinatario\":\"X\",\"agendado_para\":\""
                + hoje.plusDays(365) + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGENDADO"));
    }

    /** limite_pagamento negativo ou zero vindo do Onboarding: tudo vai para aprovação, nunca vira "sem limite". */
    @Test
    void limiteNegativoOuZeroFicaPendenteDeAprovacao() throws Exception {
        Account conta = conta(empresaA, "1000.00");
        for (String limite : List.of("-1", "-0.01", "0", "-99999999999")) {
            String op = membro(empresaA, "op" + limite, "OPERADOR", limite, "ATIVO",
                    List.of(Permissoes.VER_FINANCEIRO, Permissoes.PAGAR));
            postAs(op, "/me/payments", "{\"tipo\":\"PIX\",\"valor\":0.01,\"destinatario\":\"X\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(PaymentStatus.PENDENTE_APROVACAO.name()));
        }
        assertThat(saldo(conta)).isEqualByComparingTo("1000.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(conta.getId())).isEmpty();
    }
}
