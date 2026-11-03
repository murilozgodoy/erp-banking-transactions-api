package com.erpbanking.transactions.portal.read;

import static org.mockito.ArgumentMatchers.anyString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.erpbanking.transactions.portal.CurrentCustomer;
import com.erpbanking.transactions.config.SecurityConfig;
import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.OnboardingPendingException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MeReadControllerTest {

    static final Long C = 7201L;

    @Autowired MockMvc mockMvc;
    @Autowired PortalFixtures f;
    @MockBean CurrentCustomerResolver customers;

    PortalFixtures.Ids ids;

    @BeforeEach
    void setUp() {
        when(customers.requirePermission(anyString())).thenReturn(new CurrentCustomer("user", C, "APROVADO"));
        ids = f.cenario(C, LocalDate.now(PortalCalculations.ZONE));
    }

    static MockHttpServletRequestBuilder me(String path) {
        return get(path).with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF)));
    }

    @Test
    void overviewEmSnakeCase() throws Exception {
        mockMvc.perform(me("/me/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(1000.0))
                .andExpect(jsonPath("$.conta.agencia").value("0001"))
                .andExpect(jsonPath("$.conta.numero").value(PortalCalculations.numeroConta(ids.account())))
                .andExpect(jsonPath("$.receita_30d").value(400.0))
                .andExpect(jsonPath("$.receita_30d_variacao_pct").value(100.0))
                .andExpect(jsonPath("$.ticket_medio_30d").value(200.0))
                .andExpect(jsonPath("$.recebiveis_90d").value(290.0))
                .andExpect(jsonPath("$.a_pagar_30d").value(1750.0))
                .andExpect(jsonPath("$.atrasados_total").value(1200.0))
                .andExpect(jsonPath("$.estoque_valor").value(250.0))
                .andExpect(jsonPath("$.pendencias_count").value(6))
                .andExpect(jsonPath("$.fluxo_caixa", hasSize(60)))
                .andExpect(jsonPath("$.fluxo_caixa[0].saldo_projetado").exists())
                .andExpect(jsonPath("$.fluxo_caixa[0].entradas").exists())
                .andExpect(jsonPath("$.fluxo_caixa[0].data").value(LocalDate.now(PortalCalculations.ZONE).toString()));
    }

    @Test
    void salesReceivablesPayables() throws Exception {
        mockMvc.perform(me("/me/sales").param("group_by", "month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serie[0].periodo").exists())
                .andExpect(jsonPath("$.por_forma_pagamento[0].pct").exists())
                .andExpect(jsonPath("$.por_canal[0].canal").exists())
                .andExpect(jsonPath("$.top_produtos[0].nome").exists())
                .andExpect(jsonPath("$.total").value(400.0))
                .andExpect(jsonPath("$.ticket_medio").value(200.0));
        mockMvc.perform(me("/me/receivables").param("status", "PREVISTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens", hasSize(2)))
                .andExpect(jsonPath("$.itens[0].valor_bruto").value(300.0))
                .andExpect(jsonPath("$.itens[0].valor_liquido").value(290.0))
                .andExpect(jsonPath("$.total_previsto").value(338.0))
                .andExpect(jsonPath("$.por_semana[0].semana").exists());
        mockMvc.perform(me("/me/payables").param("status", "ATRASADO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens", hasSize(2)))
                .andExpect(jsonPath("$.itens[0].codigo_barras").exists())
                .andExpect(jsonPath("$.itens[0].status").value("ATRASADO"))
                .andExpect(jsonPath("$.total_aberto").value(1549.0))
                .andExpect(jsonPath("$.total_atrasado").value(1200.0))
                .andExpect(jsonPath("$.proximos_7d").value(550.0));
    }

    @Test
    void statementInventoryPendencias() throws Exception {
        mockMvc.perform(me("/me/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].saldo_apos").value(1000.0))
                .andExpect(jsonPath("$[1].descricao").value("PIX para Fornecedor Alfa · Insumos"))
                .andExpect(jsonPath("$[1].tipo").value("DEBIT"));
        mockMvc.perform(me("/me/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].custo_unitario").value(5.0))
                .andExpect(jsonPath("$.itens[0].abaixo_minimo").value(true))
                .andExpect(jsonPath("$.itens[1].giro_dias").value(120))
                .andExpect(jsonPath("$.valor_parado").value(200.0))
                .andExpect(jsonPath("$.abaixo_minimo").value(1))
                .andExpect(jsonPath("$.cobertura_media_dias").value(35.0));
        mockMvc.perform(me("/me/pendencias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].id").value("payable-" + ids.atrasadaAntiga()))
                .andExpect(jsonPath("$[0].acao.rota").value("/app/pagamentos?conta=" + ids.atrasadaAntiga()))
                .andExpect(jsonPath("$[0].acao.credito.produto").value("CAPITAL_DE_GIRO"))
                .andExpect(jsonPath("$[1].acao.credito").doesNotExist())
                .andExpect(jsonPath("$[3].tipo").value("PROPOSTA_CREDITO"))
                .andExpect(jsonPath("$[4].titulo", startsWith("Estoque baixo")));
    }

    @Test
    void parametrosInvalidosViram400() throws Exception {
        mockMvc.perform(me("/me/sales").param("group_by", "ano"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PARAMETRO_INVALIDO"));
        mockMvc.perform(me("/me/statement").param("from", "ontem"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensagem").value("Valor inválido para from"));
        mockMvc.perform(me("/me/payables").param("status", "XPTO")).andExpect(status().isBadRequest());
    }

    @Test
    void periodoDeVendasLimitadoA400Dias() throws Exception {
        mockMvc.perform(me("/me/sales").param("from", "2025-01-01").param("to", "2026-02-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PARAMETRO_INVALIDO"))
                .andExpect(jsonPath("$.mensagem").value("Período máximo de 400 dias"));
        mockMvc.perform(me("/me/sales").param("from", "2025-01-01").param("to", "2026-02-04")
                        .param("group_by", "month"))
                .andExpect(status().isOk());
    }

    @Test
    void onboardingPendenteVira409() throws Exception {
        when(customers.requirePermission(anyString())).thenThrow(new OnboardingPendingException("EM_ANALISE"));
        mockMvc.perform(me("/me/overview"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ONBOARDING_PENDENTE"))
                .andExpect(jsonPath("$.status").value("EM_ANALISE"));
    }
}
