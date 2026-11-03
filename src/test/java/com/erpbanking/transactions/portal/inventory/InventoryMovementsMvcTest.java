package com.erpbanking.transactions.portal.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.domain.InventoryMovementType;
import com.erpbanking.transactions.portal.CurrentCustomer;
import com.erpbanking.transactions.portal.Membro;
import com.erpbanking.transactions.portal.OnboardingClient;
import com.erpbanking.transactions.portal.OnboardingClient.MeResponse;
import com.erpbanking.transactions.portal.OnboardingClient.MembroResponse;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.PortalExceptions.EstoqueInsuficienteException;
import com.erpbanking.transactions.portal.PortalMetrics;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient.ScoreAnalysis;
import com.erpbanking.transactions.portal.inventory.InventoryDtos.MovementRequest;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.InventoryMovementRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/** {@code POST/GET /me/inventory/{id}/movements} com o resolver real e o Onboarding {@code GET /me} mockado. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryMovementsMvcTest {

    private static final AtomicLong IDS = new AtomicLong(8_100_000);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired InventoryItemRepository items;
    @Autowired InventoryMovementRepository movements;
    @Autowired InventoryMovementService service;
    @Autowired MeterRegistry registry;
    @MockBean OnboardingClient onboarding;
    @MockBean ScoreAnalysisClient scoreAnalysis;

    Long customerId;
    String sub;

    @BeforeEach
    void setUp() {
        customerId = IDS.incrementAndGet();
        sub = "auth0|" + customerId + "-op";
        when(scoreAnalysis.myAnalysis()).thenReturn(Optional.of(new ScoreAnalysis(742.0, "MEDIO", "APROVADO")));
        membro("OPERADOR", "Olga Operadora", Permissoes.VER_FINANCEIRO, Permissoes.GERENCIAR_ESTOQUE);
    }

    private void membro(String papel, String nome, String... permissoes) {
        when(onboarding.me()).thenReturn(new MeResponse(customerId, "APROVADO",
                new MembroResponse(1L, papel, List.of(permissoes), null, "ATIVO", nome), null));
    }

    private static MockHttpServletRequestBuilder as(String sub, MockHttpServletRequestBuilder b) {
        return b.with(jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF),
                new SimpleGrantedAuthority(SecurityConfig.WRITE_SELF)));
    }

    private ResultActions mover(Long itemId, String body) throws Exception {
        return mockMvc.perform(as(sub, post("/me/inventory/" + itemId + "/movements"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getAs(String path) throws Exception {
        return mockMvc.perform(as(sub, get(path)));
    }

    private InventoryItem item(int quantidade, int minimo, String custo) {
        InventoryItem i = new InventoryItem();
        i.setCustomerId(customerId);
        i.setSku("SKU-" + customerId + "-" + System.nanoTime());
        i.setNome("Parafuso sextavado");
        i.setQuantidade(quantidade);
        i.setMinimo(minimo);
        i.setCustoUnitario(new BigDecimal(custo));
        i.setPrecoVenda(new BigDecimal("12.90"));
        i.setGiroDias(20);
        return items.save(i);
    }

    private InventoryItem reload(InventoryItem i) {
        return items.findById(i.getId()).orElseThrow();
    }

    private double contador(InventoryMovementType tipo) {
        return registry.get(PortalMetrics.INVENTORY_MOVEMENTS).tag("tipo", tipo.name()).counter().count();
    }

    @Test
    void entradaSomaRecalculaCustoMedioEDevolveMovimentoEItem() throws Exception {
        InventoryItem i = item(10, 5, "5.00");
        double antes = contador(InventoryMovementType.ENTRADA);

        mover(i.getId(), "{\"tipo\":\"ENTRADA\",\"quantidade\":30,\"custo_unitario\":7.00,\"motivo\":\"NF 123\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movimento.id").isNumber())
                .andExpect(jsonPath("$.movimento.item_id").value(i.getId()))
                .andExpect(jsonPath("$.movimento.tipo").value("ENTRADA"))
                .andExpect(jsonPath("$.movimento.quantidade").value(30))
                .andExpect(jsonPath("$.movimento.quantidade_anterior").value(10))
                .andExpect(jsonPath("$.movimento.quantidade_final").value(40))
                .andExpect(jsonPath("$.movimento.custo_unitario").value(7.00))
                .andExpect(jsonPath("$.movimento.motivo").value("NF 123"))
                .andExpect(jsonPath("$.movimento.created_by_nome").value("Olga Operadora"))
                .andExpect(jsonPath("$.movimento.created_at").isString())
                .andExpect(jsonPath("$.item.id").value(i.getId()))
                .andExpect(jsonPath("$.item.sku").value(i.getSku()))
                .andExpect(jsonPath("$.item.nome").value("Parafuso sextavado"))
                .andExpect(jsonPath("$.item.quantidade").value(40))
                .andExpect(jsonPath("$.item.minimo").value(5))
                // (10·5,00 + 30·7,00) / 40 = 6,50
                .andExpect(jsonPath("$.item.custo_unitario").value(6.50))
                .andExpect(jsonPath("$.item.preco_venda").value(12.90))
                .andExpect(jsonPath("$.item.giro_dias").value(20))
                .andExpect(jsonPath("$.item.cobertura_dias").value(20))
                .andExpect(jsonPath("$.item.abaixo_minimo").value(false))
                .andExpect(jsonPath("$.item.parado").value(false))
                .andExpect(jsonPath("$.item.ultima_venda").hasJsonPath());

        InventoryItem depois = reload(i);
        assertThat(depois.getQuantidade()).isEqualTo(40);
        assertThat(depois.getCustoUnitario()).isEqualByComparingTo("6.50");
        assertThat(movements.findAll().stream().filter(m -> m.getItemId().equals(i.getId())))
                .singleElement().satisfies(m -> assertThat(m.getCreatedBySub()).isEqualTo(sub));
        assertThat(contador(InventoryMovementType.ENTRADA)).isEqualTo(antes + 1);
    }

    @Test
    void custoMedioUsaHalfEven() throws Exception {
        InventoryItem i = item(3, 1, "1.00");
        // (3·1,00 + 3·1,01) / 6 = 1,005 → HALF_EVEN → 1,00
        mover(i.getId(), "{\"tipo\":\"ENTRADA\",\"quantidade\":3,\"custo_unitario\":1.01}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.custo_unitario").value(1.00));
        assertThat(reload(i).getCustoUnitario()).isEqualByComparingTo("1.00");
    }

    @Test
    void entradaSemCustoMantemOCusto() throws Exception {
        InventoryItem i = item(4, 1, "3.33");
        mover(i.getId(), "{\"tipo\":\"ENTRADA\",\"quantidade\":6}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movimento.custo_unitario").isEmpty())
                .andExpect(jsonPath("$.movimento.motivo").isEmpty())
                .andExpect(jsonPath("$.item.quantidade").value(10))
                .andExpect(jsonPath("$.item.custo_unitario").value(3.33));
    }

    @Test
    void saidaSubtrai() throws Exception {
        InventoryItem i = item(10, 2, "5.00");
        double antes = contador(InventoryMovementType.SAIDA);
        mover(i.getId(), "{\"tipo\":\"SAIDA\",\"quantidade\":4,\"motivo\":\"Venda balcão\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movimento.tipo").value("SAIDA"))
                .andExpect(jsonPath("$.movimento.quantidade").value(4))
                .andExpect(jsonPath("$.movimento.quantidade_anterior").value(10))
                .andExpect(jsonPath("$.movimento.quantidade_final").value(6))
                .andExpect(jsonPath("$.item.quantidade").value(6))
                .andExpect(jsonPath("$.item.custo_unitario").value(5.00));
        assertThat(reload(i).getQuantidade()).isEqualTo(6);
        assertThat(contador(InventoryMovementType.SAIDA)).isEqualTo(antes + 1);
    }

    @Test
    void saidaAcimaDoEstoqueDa422SemAlterar() throws Exception {
        InventoryItem i = item(3, 1, "5.00");
        mover(i.getId(), "{\"tipo\":\"SAIDA\",\"quantidade\":4}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ESTOQUE_INSUFICIENTE"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.disponivel").value(3));
        assertThat(reload(i).getQuantidade()).isEqualTo(3);
        assertThat(movements.countByItemId(i.getId())).isZero();
    }

    @Test
    void ajusteDefineQuantidadeFinalInclusiveZero() throws Exception {
        InventoryItem i = item(17, 5, "5.00");
        double antes = contador(InventoryMovementType.AJUSTE);
        mover(i.getId(), "{\"tipo\":\"AJUSTE\",\"quantidade\":12,\"motivo\":\"Inventário\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movimento.quantidade").value(12))
                .andExpect(jsonPath("$.movimento.quantidade_anterior").value(17))
                .andExpect(jsonPath("$.movimento.quantidade_final").value(12));
        mover(i.getId(), "{\"tipo\":\"AJUSTE\",\"quantidade\":0}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movimento.quantidade").value(0))
                .andExpect(jsonPath("$.movimento.quantidade_anterior").value(12))
                .andExpect(jsonPath("$.movimento.quantidade_final").value(0))
                .andExpect(jsonPath("$.item.quantidade").value(0))
                .andExpect(jsonPath("$.item.abaixo_minimo").value(true));
        assertThat(reload(i).getQuantidade()).isZero();
        assertThat(contador(InventoryMovementType.AJUSTE)).isEqualTo(antes + 2);
    }

    @Test
    void validacaoDa400ComCamposESemEfeito() throws Exception {
        InventoryItem i = item(10, 1, "5.00");
        String[][] casos = {
                {"{\"tipo\":\"ENTRADA\",\"quantidade\":0}", "quantidade"},
                {"{\"tipo\":\"SAIDA\",\"quantidade\":0}", "quantidade"},
                {"{\"tipo\":\"ENTRADA\",\"quantidade\":-1}", "quantidade"},
                {"{\"tipo\":\"AJUSTE\",\"quantidade\":-1}", "quantidade"},
                {"{\"tipo\":\"ENTRADA\",\"quantidade\":1.5}", "quantidade"},
                {"{\"tipo\":\"ENTRADA\",\"quantidade\":1000001}", "quantidade"},
                {"{\"tipo\":\"AJUSTE\",\"quantidade\":99999999999999999999}", "quantidade"},
                {"{\"tipo\":\"ENTRADA\"}", "quantidade"},
                {"{\"quantidade\":1}", "tipo"},
                {"{\"tipo\":\"PERDA\",\"quantidade\":1}", "tipo"},
                {"{\"tipo\":\"SAIDA\",\"quantidade\":1,\"custo_unitario\":2.00}", "custo_unitario"},
                {"{\"tipo\":\"AJUSTE\",\"quantidade\":1,\"custo_unitario\":2.00}", "custo_unitario"},
                {"{\"tipo\":\"ENTRADA\",\"quantidade\":1,\"custo_unitario\":0}", "custo_unitario"},
                {"{\"tipo\":\"ENTRADA\",\"quantidade\":1,\"custo_unitario\":1.234}", "custo_unitario"},
                {"{\"tipo\":\"ENTRADA\",\"quantidade\":1,\"motivo\":\"" + "x".repeat(201) + "\"}", "motivo"},
        };
        for (String[] caso : casos) {
            mover(i.getId(), caso[0])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("validation_failed"))
                    .andExpect(jsonPath("$.campos." + caso[1]).exists());
        }
        assertThat(reload(i).getQuantidade()).isEqualTo(10);
        assertThat(movements.countByItemId(i.getId())).isZero();
    }

    @Test
    void historicoMaisRecentePrimeiro() throws Exception {
        InventoryItem i = item(10, 1, "5.00");
        InventoryItem outro = item(10, 1, "5.00");
        mover(i.getId(), "{\"tipo\":\"ENTRADA\",\"quantidade\":5}").andExpect(status().isCreated());
        mover(i.getId(), "{\"tipo\":\"SAIDA\",\"quantidade\":2}").andExpect(status().isCreated());
        mover(i.getId(), "{\"tipo\":\"AJUSTE\",\"quantidade\":20}").andExpect(status().isCreated());
        mover(outro.getId(), "{\"tipo\":\"SAIDA\",\"quantidade\":1}").andExpect(status().isCreated());

        getAs("/me/inventory/" + i.getId() + "/movements")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tipo").value("AJUSTE"))
                .andExpect(jsonPath("$[0].quantidade_final").value(20))
                .andExpect(jsonPath("$[1].tipo").value("SAIDA"))
                .andExpect(jsonPath("$[2].tipo").value("ENTRADA"))
                .andExpect(jsonPath("$[2].quantidade_anterior").value(10))
                .andExpect(jsonPath("$[*].item_id", not(hasItem(outro.getId().intValue()))));
    }

    @Test
    void historicoExigeVerFinanceiro() throws Exception {
        InventoryItem i = item(10, 1, "5.00");
        membro("OPERADOR", "Sem leitura", Permissoes.GERENCIAR_ESTOQUE);
        sub = sub + "-sem-leitura";
        getAs("/me/inventory/" + i.getId() + "/movements")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SEM_PERMISSAO"))
                .andExpect(jsonPath("$.permissao").value("ver_financeiro"));
    }

    /** Indicadores calculados na hora: a pendência ESTOQUE_BAIXO some depois da ENTRADA, e o overview acompanha. */
    @Test
    void entradaRecalculaPendenciasInventoryEOverview() throws Exception {
        InventoryItem i = item(2, 10, "5.00");
        String pendencia = "inventory-low-" + i.getId();

        getAs("/me/pendencias").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(pendencia)));
        getAs("/me/inventory").andExpect(status().isOk())
                .andExpect(jsonPath("$.abaixo_minimo").value(1))
                .andExpect(jsonPath("$.valor_total").value(10.00));
        JsonNode antes = json.readTree(getAs("/me/overview").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(antes.get("estoque_valor").decimalValue()).isEqualByComparingTo("10.00");

        mover(i.getId(), "{\"tipo\":\"ENTRADA\",\"quantidade\":8}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.abaixo_minimo").value(false));

        getAs("/me/pendencias").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(pendencia))));
        getAs("/me/inventory").andExpect(status().isOk())
                .andExpect(jsonPath("$.abaixo_minimo").value(0))
                .andExpect(jsonPath("$.valor_total").value(50.00));
        JsonNode depois = json.readTree(getAs("/me/overview").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(depois.get("estoque_valor").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(depois.get("pendencias_count").asInt()).isEqualTo(antes.get("pendencias_count").asInt() - 1);
    }

    /** SAIDAs concorrentes no mesmo item: o lock do item serializa e o estoque nunca fica negativo. */
    @Test
    void saidasConcorrentesNuncaDeixamEstoqueNegativo() throws Exception {
        InventoryItem i = item(10, 0, "5.00");
        CurrentCustomer cliente = new CurrentCustomer(sub, customerId, "APROVADO",
                new Membro(1L, "OPERADOR", Permissoes.TODAS, null, "ATIVO", "Olga Operadora"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.move(cliente, i.getId(),
                            new MovementRequest(InventoryMovementType.SAIDA, new BigDecimal("3"), null, null));
                    return true;
                } catch (EstoqueInsuficienteException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(60, TimeUnit.SECONDS)) {
                ok++;
            }
        }
        pool.shutdownNow();

        assertThat(ok).isEqualTo(3);
        assertThat(reload(i).getQuantidade()).isEqualTo(1);
        assertThat(movements.countByItemId(i.getId())).isEqualTo(3);
    }
}
