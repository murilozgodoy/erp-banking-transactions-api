package com.erpbanking.transactions.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.erpbanking.transactions.config.SecurityConfig;
import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.portal.MemberSuspendedException;
import com.erpbanking.transactions.portal.Permissoes;

/** Autorização nas rotas /me: scopes do token (read:self/write:self) e membro SUSPENSO. */
class MeAuthorizationMvcTest extends SecurityTestSupport {

    static final List<String> GETS = List.of("/me/overview", "/me/sales", "/me/receivables", "/me/payables",
            "/me/statement", "/me/inventory", "/me/pendencias", "/me/payments", "/me/payments/pending",
            "/me/credit/offers", "/me/credit/requests");

    /** Rotas POST com um corpo válido (o 403 não pode depender de o corpo estar errado). */
    static Map<String, String> posts(long paymentId, long requestId) {
        return Map.of(
                "/me/payments", "{\"tipo\":\"PIX\",\"valor\":10.00,\"destinatario\":\"X\"}",
                "/me/payments/" + paymentId + "/approve", "{}",
                "/me/payments/" + paymentId + "/reject", "{}",
                "/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":6}",
                "/me/credit/requests", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":6}",
                "/me/credit/requests/" + requestId + "/confirm", "{}");
    }

    private static MockHttpServletRequestBuilder comAutoridades(MockHttpServletRequestBuilder b, String... auths) {
        return b.with(jwt().authorities(java.util.Arrays.stream(auths).<org.springframework.security.core.GrantedAuthority>map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    void semReadSelfGetDa403() throws Exception {
        for (String rota : GETS) {
            mockMvc.perform(comAutoridades(get(rota), SecurityConfig.WRITE_SELF)).andExpect(status().isForbidden());
            mockMvc.perform(get(rota)).andExpect(status().isUnauthorized());
        }
        verify(onboarding, never()).me();
    }

    @Test
    void semWriteSelfPostDa403() throws Exception {
        for (Map.Entry<String, String> e : posts(1L, 1L).entrySet()) {
            mockMvc.perform(comAutoridades(post(e.getKey()), SecurityConfig.READ_SELF)
                    .contentType(MediaType.APPLICATION_JSON).content(e.getValue())).andExpect(status().isForbidden());
        }
        verify(onboarding, never()).me();
    }

    @Test
    void tokenDeStaffSemReadSelfNaoUsaMe() throws Exception {
        String[] staff = {SecurityConfig.READ_ACCOUNTS, SecurityConfig.WRITE_ACCOUNTS, SecurityConfig.READ_CREDITS,
                SecurityConfig.WRITE_CREDITS};
        for (String rota : GETS) {
            mockMvc.perform(comAutoridades(get(rota), staff)).andExpect(status().isForbidden());
        }
        for (Map.Entry<String, String> e : posts(1L, 1L).entrySet()) {
            mockMvc.perform(comAutoridades(post(e.getKey()), staff).contentType(MediaType.APPLICATION_JSON)
                    .content(e.getValue())).andExpect(status().isForbidden());
        }
        verify(onboarding, never()).me();
    }

    /** 403 do Onboarding (MEMBRO_SUSPENSO) em todas as rotas /me, inclusive POST, sem cache e sem efeito. */
    @Test
    void membroSuspensoNoOnboardingDa403EmTodasAsRotas() throws Exception {
        Account conta = conta(empresaA, "1000.00");
        Payment pendente = pagamentoPendente(empresaA, "Fornecedor", "600.00", null, sub(empresaA, "outro"));
        long pedido = pedidoAguardando(empresaA, "500.00").getId();
        String suspenso = sub(empresaA, "suspenso");
        perfis.put(suspenso, () -> {
            throw new MemberSuspendedException();
        });
        assertSuspensoEmTudo(suspenso, pendente.getId(), pedido);
        assertThat(saldo(conta)).isEqualByComparingTo("1000.00");
        assertThat(payments.findById(pendente.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDENTE_APROVACAO);
        assertThat(pagamentosDa(empresaA)).isEqualTo(1);
        assertThat(simulacoesDa(empresaA)).isEqualTo(1);
    }

    /** {@code membro.status = SUSPENSO} num 200 do Onboarding também bloqueia (defesa em profundidade). */
    @Test
    void membroComStatusSuspensoDa403EmTodasAsRotas() throws Exception {
        Payment pendente = pagamentoPendente(empresaA, "Fornecedor", "600.00", null, sub(empresaA, "outro"));
        long pedido = pedidoAguardando(empresaA, "500.00").getId();
        String suspenso = membro(empresaA, "suspenso-200", "FINANCEIRO", null, "SUSPENSO",
                List.copyOf(Permissoes.TODAS));
        assertSuspensoEmTudo(suspenso, pendente.getId(), pedido);
    }

    /** Cada chamada vai ao Onboarding (nada em cache) e responde 403 MEMBRO_SUSPENSO. */
    private void assertSuspensoEmTudo(String sub, long paymentId, long pedido) throws Exception {
        clearInvocations(onboarding);
        int chamadas = 0;
        for (String rota : GETS) {
            getAs(sub, rota).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("MEMBRO_SUSPENSO"));
            chamadas++;
        }
        for (Map.Entry<String, String> e : posts(paymentId, pedido).entrySet()) {
            postAs(sub, e.getKey(), e.getValue()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("MEMBRO_SUSPENSO"));
            chamadas++;
        }
        verify(onboarding, times(chamadas)).me();
    }

    /** Sem aprovar_pagamentos: 403 SEM_PERMISSAO na bandeja, em approve e em reject, e nada muda. */
    @Test
    void permissaoDeAprovarExigidaEmApproveERejectEPending() throws Exception {
        Payment pendente = pagamentoPendente(empresaA, "Fornecedor", "600.00", null, sub(empresaA, "outro"));
        String operador = membro(empresaA, "operador", "OPERADOR", "100.00", "ATIVO",
                List.of(Permissoes.VER_FINANCEIRO, Permissoes.PAGAR));
        getAs(operador, "/me/payments/pending").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.permissao").value(Permissoes.APROVAR_PAGAMENTOS));
        for (String acao : List.of("approve", "reject")) {
            postAs(operador, "/me/payments/" + pendente.getId() + "/" + acao, "{}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("SEM_PERMISSAO"));
        }
        assertThat(payments.findById(pendente.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDENTE_APROVACAO);
    }
}
