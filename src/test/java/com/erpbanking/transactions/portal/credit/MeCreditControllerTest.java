package com.erpbanking.transactions.portal.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import com.erpbanking.transactions.domain.CreditSimulation;
import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.PaymentMethod;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.domain.Sale;
import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient.ScoreAnalysis;
import com.erpbanking.transactions.repository.ContractRepository;
import com.erpbanking.transactions.repository.CreditSimulationRepository;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.SaleRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeCreditControllerTest {

    private static final AtomicLong IDS = new AtomicLong(800_000);

    @Autowired MockMvc mockMvc;
    @Autowired SaleRepository sales;
    @Autowired ReceivableRepository receivables;
    @Autowired InventoryItemRepository inventory;
    @Autowired CreditSimulationRepository simulations;
    @Autowired ContractRepository contracts;
    @MockBean CurrentCustomerResolver resolver;
    @MockBean ScoreAnalysisClient scoreAnalysis;

    Long customerId;

    @BeforeEach
    void setUp() {
        customerId = IDS.incrementAndGet();
        when(resolver.requireCustomerId()).thenReturn(customerId);
        analysis("MEDIO", "APROVADO");
    }

    private void analysis(String faixa, String status) {
        when(scoreAnalysis.myAnalysis()).thenReturn(faixa == null ? Optional.empty()
                : Optional.of(new ScoreAnalysis(742.0, faixa, status)));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF),
                new SimpleGrantedAuthority(SecurityConfig.WRITE_SELF)));
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(authed(post(path)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void sale(Long owner, LocalDate data, String valor) {
        Sale s = new Sale();
        s.setCustomerId(owner);
        s.setData(data);
        s.setValor(new BigDecimal(valor));
        s.setCanal("Loja física");
        s.setFormaPagamento(PaymentMethod.PIX);
        s.setParcelas(1);
        sales.save(s);
    }

    private Receivable receivable(Long owner, String liquido, ReceivableStatus status) {
        Receivable r = new Receivable();
        r.setCustomerId(owner);
        r.setVencimento(LocalDate.now().plusDays(20));
        r.setValorBruto(new BigDecimal(liquido));
        r.setTaxaAdquirente(BigDecimal.ZERO);
        r.setValorLiquido(new BigDecimal(liquido));
        r.setAdquirente("Cielo");
        r.setStatus(status);
        return receivables.save(r);
    }

    private void item(String qtd, String custo) {
        InventoryItem i = new InventoryItem();
        i.setCustomerId(customerId);
        i.setSku("SKU-" + qtd);
        i.setNome("Item");
        i.setQuantidade(Integer.valueOf(qtd));
        i.setMinimo(1);
        i.setCustoUnitario(new BigDecimal(custo));
        i.setPrecoVenda(new BigDecimal(custo));
        i.setGiroDias(30);
        inventory.save(i);
    }

    private void seed() {
        LocalDate today = LocalDate.now(PortalCreditService.ZONE);
        sale(customerId, today, "6000.00");
        sale(customerId, today.minusDays(10), "4000.00");
        sale(customerId, today.minusDays(40), "99999.00"); // fora dos 30 dias
        receivable(customerId, "5000.00", ReceivableStatus.PREVISTO);
        receivable(customerId, "999.00", ReceivableStatus.RECEBIDO);
        item("10", "100.00");
        item("5", "200.00");
    }

    @Test
    void ofertasComLimitesETaxas() throws Exception {
        seed();
        mockMvc.perform(authed(get("/me/credit/offers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].produto").value("ANTECIPACAO_RECEBIVEIS"))
                .andExpect(jsonPath("$[0].limite").value(4000.00))
                .andExpect(jsonPath("$[0].taxa_mensal_a_partir").value(2.09))
                .andExpect(jsonPath("$[0].prazo_max_meses").value(6))
                .andExpect(jsonPath("$[1].produto").value("CAPITAL_DE_GIRO"))
                .andExpect(jsonPath("$[1].limite").value(10000.00))
                .andExpect(jsonPath("$[2].produto").value("ALAVANCAGEM"))
                .andExpect(jsonPath("$[2].limite").value(30000.00))
                .andExpect(jsonPath("$[2].taxa_mensal_a_partir").value(2.79))
                .andExpect(jsonPath("$[3].produto").value("FINANCIAMENTO_ESTOQUE"))
                .andExpect(jsonPath("$[3].limite").value(1200.00));
    }

    @Test
    void ofertasVaziasParaMuitoAltoEConservadorasSemAnalise() throws Exception {
        analysis("MUITO_ALTO", "APROVADO");
        mockMvc.perform(authed(get("/me/credit/offers"))).andExpect(jsonPath("$", hasSize(0)));
        analysis(null, null);
        mockMvc.perform(authed(get("/me/credit/offers")))
                .andExpect(jsonPath("$[1].taxa_mensal_a_partir").value(3.49))
                .andExpect(jsonPath("$[1].limite").value(0));
    }

    @Test
    void simulacaoPreAprovadaViraPedidoAprovadoComContrato() throws Exception {
        seed();
        String body = postJson("/me/credit/simulations",
                "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":8000,\"prazo_meses\":12}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.produto").value("CAPITAL_DE_GIRO"))
                .andExpect(jsonPath("$.valor_solicitado").value(8000.00))
                .andExpect(jsonPath("$.taxa_mensal").value(2.49))
                .andExpect(jsonPath("$.cet_anual").isNumber())
                .andExpect(jsonPath("$.iof").isNumber())
                .andExpect(jsonPath("$.valor_liquido").isNumber())
                .andExpect(jsonPath("$.parcelas", hasSize(12)))
                .andExpect(jsonPath("$.parcelas[0].n").value(1))
                .andExpect(jsonPath("$.parcelas[0].vencimento").exists())
                .andExpect(jsonPath("$.parcelas[0].amortizacao").isNumber())
                .andExpect(jsonPath("$.parcelas[0].juros").value(199.20))
                .andExpect(jsonPath("$.total_pago").isNumber())
                .andExpect(jsonPath("$.aprovacao").value("PRE_APROVADO"))
                .andExpect(jsonPath("$.limite_disponivel").value(10000.00))
                .andExpect(jsonPath("$.validade").value(LocalDate.now(PortalCreditService.ZONE).plusDays(7).toString()))
                .andReturn().getResponse().getContentAsString();
        long simId = Long.parseLong(body.replaceAll("^\\{\"id\":(\\d+).*", "$1"));

        String resp = postJson("/me/credit/requests", "{\"simulation_id\":" + simId + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.taxaJuros").value(0.0249))
                .andExpect(jsonPath("$.valorAprovado").value(8000.00))
                .andExpect(jsonPath("$.scoreSnapshot").value(742.0))
                .andReturn().getResponse().getContentAsString();
        long crId = Long.parseLong(resp.replaceAll("^\\{\"id\":(\\d+).*", "$1"));
        assertThat(contracts.findByCreditRequestId(crId)).isPresent();
        assertThat(simulations.findById(simId).orElseThrow().getCreditRequestId()).isEqualTo(crId);

        postJson("/me/credit/requests", "{\"simulation_id\":" + simId + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULACAO_JA_UTILIZADA"));

        mockMvc.perform(authed(get("/me/credit/requests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(crId));
    }

    @Test
    void acimaDoLimiteViraAnaliseERevisao() throws Exception {
        seed();
        postJson("/me/credit/requests", "{\"produto\":\"ALAVANCAGEM\",\"valor\":50000,\"prazo_meses\":36}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REVISAO"))
                .andExpect(jsonPath("$.valorAprovado").value(0))
                .andExpect(jsonPath("$.motivo").value(org.hamcrest.Matchers.containsString("acima do limite")));
    }

    @Test
    void semAnaliseViraAnaliseEMuitoAltoViraNegado() throws Exception {
        analysis(null, null);
        postJson("/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":100,\"prazo_meses\":3}")
                .andExpect(jsonPath("$.aprovacao").value("ANALISE"))
                .andExpect(jsonPath("$.taxa_mensal").value(3.49));
        postJson("/me/credit/requests", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":100,\"prazo_meses\":3}")
                .andExpect(jsonPath("$.status").value("REVISAO"))
                .andExpect(jsonPath("$.motivo").value(org.hamcrest.Matchers.containsString("não disponível")));

        analysis("ALTO", "APROVADO");
        postJson("/me/credit/requests", "{\"produto\":\"CONSOLIDACAO_DIVIDAS\",\"valor\":1,\"prazo_meses\":3}")
                .andExpect(jsonPath("$.status").value("REVISAO"))
                .andExpect(jsonPath("$.motivo").value(org.hamcrest.Matchers.containsString("risco ALTO")));

        analysis("MUITO_ALTO", "APROVADO");
        postJson("/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":100,\"prazo_meses\":3}")
                .andExpect(jsonPath("$.aprovacao").value("NEGADO"))
                .andExpect(jsonPath("$.limite_disponivel").value(0));
        postJson("/me/credit/requests", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":100,\"prazo_meses\":3}")
                .andExpect(jsonPath("$.status").value("NEGADO"));
    }

    @Test
    void antecipacaoComRecebiveisSelecionados() throws Exception {
        Receivable a = receivable(customerId, "1000.00", ReceivableStatus.PREVISTO);
        receivable(customerId, "9000.00", ReceivableStatus.PREVISTO);
        Receivable recebido = receivable(customerId, "50.00", ReceivableStatus.RECEBIDO);
        Receivable outro = receivable(customerId + 1_000_000, "50.00", ReceivableStatus.PREVISTO);

        postJson("/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":800,\"prazo_meses\":3,"
                + "\"receivable_ids\":[" + a.getId() + "," + a.getId() + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.limite_disponivel").value(800.00))
                .andExpect(jsonPath("$.aprovacao").value("PRE_APROVADO"))
                .andExpect(jsonPath("$.taxa_mensal").value(2.09));
        postJson("/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":800,\"prazo_meses\":3}")
                .andExpect(jsonPath("$.limite_disponivel").value(8000.00));
        postJson("/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":800,\"prazo_meses\":3,"
                + "\"receivable_ids\":[" + recebido.getId() + "]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECEBIVEL_INDISPONIVEL"));
        postJson("/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":800,\"prazo_meses\":3,"
                + "\"receivable_ids\":[" + outro.getId() + "]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECEBIVEL_NAO_ENCONTRADO"));
    }

    @Test
    void validacoesEErros() throws Exception {
        postJson("/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":800,\"prazo_meses\":7}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
        postJson("/me/credit/simulations", "{\"produto\":\"CARTAO\",\"valor\":800,\"prazo_meses\":3}")
                .andExpect(status().isBadRequest());
        postJson("/me/credit/simulations", "{\"produto\":\"CAPITAL_DE_GIRO\",\"valor\":0,\"prazo_meses\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.valor").exists());
        postJson("/me/credit/requests", "{\"produto\":\"CAPITAL_DE_GIRO\"}")
                .andExpect(status().isBadRequest());
        postJson("/me/credit/requests", "{\"simulation_id\":987654321}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SIMULACAO_NAO_ENCONTRADA"));

        CreditSimulation vencida = new CreditSimulation();
        vencida.setCustomerId(customerId);
        vencida.setProduto("CAPITAL_DE_GIRO");
        vencida.setValor(BigDecimal.TEN);
        vencida.setPrazoMeses(3);
        vencida.setTaxaMensal(new BigDecimal("2.49"));
        vencida.setAprovacao("PRE_APROVADO");
        vencida.setLimite(BigDecimal.TEN);
        vencida.setValidade(LocalDate.now(PortalCreditService.ZONE).minusDays(1));
        vencida = simulations.save(vencida);
        postJson("/me/credit/requests", "{\"simulation_id\":" + vencida.getId() + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULACAO_EXPIRADA"));
    }
}
