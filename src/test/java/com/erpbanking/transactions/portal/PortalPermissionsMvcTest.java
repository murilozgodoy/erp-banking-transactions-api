package com.erpbanking.transactions.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.erpbanking.transactions.config.SecurityConfig;
import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.CreditSimulation;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient.ScoreAnalysis;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.ContractRepository;
import com.erpbanking.transactions.repository.CreditSimulationRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.repository.SaleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Permissões por membro com o resolver real e o Onboarding {@code GET /me} mockado. Cada teste usa {@code sub}s
 * próprios, porque o resolver guarda o membro em cache por {@code sub}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalPermissionsMvcTest {

    private static final AtomicLong IDS = new AtomicLong(700_000);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired PaymentRepository payments;
    @Autowired SaleRepository sales;
    @Autowired ContractRepository contracts;
    @Autowired CreditSimulationRepository simulations;
    @Autowired MeterRegistry registry;
    @MockBean OnboardingClient onboarding;
    @MockBean ScoreAnalysisClient scoreAnalysis;

    Long customerId;

    @BeforeEach
    void setUp() {
        customerId = IDS.incrementAndGet();
        when(scoreAnalysis.myAnalysis()).thenReturn(Optional.of(new ScoreAnalysis(742.0, "MEDIO", "APROVADO")));
    }

    private String sub(String who) {
        return "auth0|" + customerId + "-" + who;
    }

    private static MockHttpServletRequestBuilder as(String sub, MockHttpServletRequestBuilder b) {
        return b.with(jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF),
                new SimpleGrantedAuthority(SecurityConfig.WRITE_SELF)));
    }

    private void membro(Long customer, String papel, String nome, String... permissoes) {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(customer, "APROVADO",
                new OnboardingClient.MembroResponse(1L, papel, List.of(permissoes), null, "ATIVO", nome), null));
    }

    private ResultActions postJson(String sub, String path, String body) throws Exception {
        return mockMvc.perform(as(sub, post(path)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getAs(String sub, String path) throws Exception {
        return mockMvc.perform(as(sub, get(path)));
    }

    private static ResultActions semPermissao(ResultActions r, String permissao) throws Exception {
        return r.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SEM_PERMISSAO"))
                .andExpect(jsonPath("$.permissao").value(permissao));
    }

    private void receitaDe(String valor) {
        Sale s = new Sale();
        s.setCustomerId(customerId);
        s.setData(LocalDate.now(PortalCreditServiceZone.ZONE));
        s.setValor(new BigDecimal(valor));
        s.setCanal("Loja física");
        s.setFormaPagamento(PaymentMethod.PIX);
        s.setParcelas(1);
        sales.save(s);
    }

    private double decisions(String status) {
        Counter c = registry.find(BusinessMetrics.CREDIT_DECISIONS).tag("status", status).counter();
        return c == null ? 0 : c.count();
    }

    private long id(ResultActions r) throws Exception {
        JsonNode node = json.readTree(r.andReturn().getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    @Test
    void semMembroEhDonoComTudoLiberado() throws Exception {
        String dono = sub("dono");
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(customerId, "APROVADO", null, "Ana Dona"));
        accounts.save(new Account(customerId, new BigDecimal("1000.00")));
        receitaDe("10000");

        getAs(dono, "/me/overview").andExpect(status().isOk());
        postJson(dono, "/me/payments", "{\"tipo\":\"PIX\",\"valor\":100,\"destinatario\":\"Maria\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFETIVADO"));
        getAs(dono, "/me/payments")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].created_by_nome").value("Ana Dona"));
        assertThat(payments.findByCustomerIdOrderByIdDesc(customerId).get(0).getCreatedBySub()).isEqualTo(dono);

        postJson(dono, "/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":6}")
                .andExpect(status().isCreated());
        postJson(dono, "/me/credit/requests", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":6}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.createdByNome").value("Ana Dona"));
        getAs(dono, "/me/credit/requests").andExpect(jsonPath("$[0].createdByNome").value("Ana Dona"));
    }

    @Test
    void leituraSoLeENaoEscreve() throws Exception {
        String leitura = sub("leitura");
        membro(customerId, "LEITURA", "Lúcia", Permissoes.VER_FINANCEIRO);

        getAs(leitura, "/me/overview").andExpect(status().isOk());
        getAs(leitura, "/me/payments").andExpect(status().isOk());
        getAs(leitura, "/me/credit/offers").andExpect(status().isOk());
        getAs(leitura, "/me/credit/requests").andExpect(status().isOk());
        semPermissao(postJson(leitura, "/me/payments", "{\"tipo\":\"PIX\",\"valor\":1,\"destinatario\":\"M\"}"),
                Permissoes.PAGAR);
        semPermissao(postJson(leitura, "/me/credit/simulations",
                "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":6}"), Permissoes.PEDIR_CREDITO);
        semPermissao(postJson(leitura, "/me/credit/requests",
                "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":6}"), Permissoes.CONTRATAR_CREDITO);
        semPermissao(postJson(leitura, "/me/credit/requests/1/confirm", ""), Permissoes.CONTRATAR_CREDITO);
        assertThat(payments.findByCustomerIdOrderByIdDesc(customerId)).isEmpty();
    }

    @Test
    void semVerFinanceiroNaoLe() throws Exception {
        String operador = sub("operador");
        membro(customerId, "OPERADOR", null, Permissoes.PAGAR);

        for (String path : List.of("/me/overview", "/me/sales", "/me/receivables", "/me/payables", "/me/statement",
                "/me/inventory", "/me/pendencias", "/me/payments", "/me/credit/offers", "/me/credit/requests")) {
            semPermissao(getAs(operador, path), Permissoes.VER_FINANCEIRO);
        }
    }

    @Test
    void membroSuspensoRecebe403MembroSuspenso() throws Exception {
        when(onboarding.me()).thenThrow(new MemberSuspendedException());
        String suspenso = sub("suspenso");

        getAs(suspenso, "/me/overview").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("MEMBRO_SUSPENSO"));
        postJson(suspenso, "/me/payments", "{\"tipo\":\"PIX\",\"valor\":1,\"destinatario\":\"M\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("MEMBRO_SUSPENSO"));
    }

    @Test
    void pedirCreditoSemContratarFicaAguardandoAteAConfirmacao() throws Exception {
        receitaDe("10000");
        String financeiro = sub("financeiro");
        membro(customerId, "FINANCEIRO", "Fábio", Permissoes.VER_FINANCEIRO, Permissoes.PEDIR_CREDITO);
        double aprovadosAntes = decisions("APROVADO");

        long pedido = id(postJson(financeiro, "/me/credit/requests",
                "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":1000,\"prazo_meses\":6}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_CONTRATACAO"))
                .andExpect(jsonPath("$.createdByNome").value("Fábio"))
                .andExpect(jsonPath("$.valorAprovado").value(0)));
        assertThat(contracts.findByCreditRequestId(pedido)).isEmpty();
        assertThat(decisions("APROVADO")).isEqualTo(aprovadosAntes);
        assertThat(registry.find(BusinessMetrics.CREDIT_DECISIONS).tag("status", "AGUARDANDO_CONTRATACAO")
                .counter()).isNull();
        getAs(financeiro, "/me/credit/requests").andExpect(jsonPath("$[0].status").value("AGUARDANDO_CONTRATACAO"));
        semPermissao(postJson(financeiro, "/me/credit/requests/" + pedido + "/confirm", ""),
                Permissoes.CONTRATAR_CREDITO);

        String outraEmpresa = sub("outra");
        membro(customerId + 1_000_000, "DONO", "Outro", Permissoes.CONTRATAR_CREDITO);
        postJson(outraEmpresa, "/me/credit/requests/" + pedido + "/confirm", "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PEDIDO_NAO_ENCONTRADO"));

        String dono = sub("dono");
        membro(customerId, "DONO", "Dona", Permissoes.TODAS.toArray(String[]::new));
        postJson(dono, "/me/credit/requests/" + pedido + "/confirm", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pedido))
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.valorAprovado").value(1000.0))
                .andExpect(jsonPath("$.createdByNome").value("Fábio"));
        assertThat(contracts.findByCreditRequestId(pedido)).isPresent();
        assertThat(decisions("APROVADO")).isEqualTo(aprovadosAntes + 1);

        postJson(dono, "/me/credit/requests/" + pedido + "/confirm", "")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PEDIDO_NAO_AGUARDANDO_CONTRATACAO"));
        postJson(dono, "/me/credit/requests/999999999/confirm", "").andExpect(status().isNotFound());
    }

    @Test
    void pedidoPorSimulacaoVencidaEhReprecificadoNaConfirmacao() throws Exception {
        receitaDe("10000");
        String financeiro = sub("financeiro");
        membro(customerId, "FINANCEIRO", "Fábio", Permissoes.VER_FINANCEIRO, Permissoes.PEDIR_CREDITO);
        long sim = id(postJson(financeiro, "/me/credit/simulations",
                "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":2000,\"prazo_meses\":6}").andExpect(status().isCreated()));
        long pedido = id(postJson(financeiro, "/me/credit/requests", "{\"simulation_id\":" + sim + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_CONTRATACAO")));
        postJson(financeiro, "/me/credit/requests", "{\"simulation_id\":" + sim + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULACAO_JA_UTILIZADA"));

        CreditSimulation vencida = simulations.findById(sim).orElseThrow();
        vencida.setValidade(LocalDate.now(PortalCreditServiceZone.ZONE).minusDays(1));
        simulations.save(vencida);

        String admin = sub("admin");
        membro(customerId, "ADMIN", "Adm", Permissoes.CONTRATAR_CREDITO);
        postJson(admin, "/me/credit/requests/" + pedido + "/confirm", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADO"));
        assertThat(simulations.findById(sim).orElseThrow().getCreditRequestId()).isNull();
        assertThat(simulations.findAll().stream().filter(s -> Long.valueOf(pedido).equals(s.getCreditRequestId())))
                .hasSize(1)
                .allSatisfy(s -> assertThat(s.getValor()).isEqualByComparingTo("2000"));
    }

    @Test
    void confirmacaoComSimulacaoValidaAcimaDoLimiteVaiParaRevisao() throws Exception {
        receitaDe("1000");
        String financeiro = sub("financeiro");
        membro(customerId, "FINANCEIRO", null, Permissoes.PEDIR_CREDITO);
        long pedido = id(postJson(financeiro, "/me/credit/requests",
                "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":800,\"prazo_meses\":6}").andExpect(status().isCreated()));
        // Outro pedido aprovado consome o limite antes da confirmação.
        String dono = sub("dono");
        membro(customerId, "DONO", null, Permissoes.CONTRATAR_CREDITO);
        postJson(dono, "/me/credit/requests", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":900,\"prazo_meses\":6}")
                .andExpect(jsonPath("$.status").value("APROVADO"));

        postJson(dono, "/me/credit/requests/" + pedido + "/confirm", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVISAO"));
    }

    /** Fuso usado pelo crédito do portal (o campo do serviço é package-private). */
    static final class PortalCreditServiceZone {
        static final java.time.ZoneId ZONE = java.time.ZoneId.of("America/Sao_Paulo");
    }
}
