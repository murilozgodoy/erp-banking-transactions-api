package com.erpbanking.transactions.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.erpbanking.transactions.domain.InventoryItem;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.repository.InventoryItemRepository;
import com.erpbanking.transactions.repository.InventoryMovementRepository;

/**
 * Movimentação de estoque: item de outra empresa responde 404 idêntico ao de um id inexistente, sem gravar nada, e
 * quem não tem {@code gerenciar_estoque} (ex.: LEITURA) recebe 403 {@code SEM_PERMISSAO}.
 */
class InventoryMovementsSecurityMvcTest extends SecurityTestSupport {

    private static final long INEXISTENTE = 987_654_321_000L;
    private static final String SEGREDO = "SEGREDO-ESTOQUE-B";

    @Autowired InventoryItemRepository items;
    @Autowired InventoryMovementRepository movements;

    InventoryItem itemA;
    InventoryItem itemB;

    @BeforeEach
    void cenario() {
        itemA = item(empresaA, "SKU-A");
        itemB = item(empresaB, SEGREDO);
        conta(empresaA, "100.00");
    }

    private InventoryItem item(Long empresa, String nome) {
        InventoryItem i = new InventoryItem();
        i.setCustomerId(empresa);
        i.setSku("SKU-" + empresa + "-" + System.nanoTime());
        i.setNome(nome);
        i.setQuantidade(10);
        i.setMinimo(2);
        i.setCustoUnitario(new BigDecimal("4.37"));
        i.setPrecoVenda(new BigDecimal("9.99"));
        i.setGiroDias(15);
        return items.save(i);
    }

    @Test
    void movimentarItemDeOutraEmpresaDa404IgualAoInexistenteSemEfeito() throws Exception {
        String donoA = dono(empresaA);
        for (String body : List.of("{\"tipo\":\"ENTRADA\",\"quantidade\":5,\"custo_unitario\":9.00}",
                "{\"tipo\":\"SAIDA\",\"quantidade\":5}", "{\"tipo\":\"AJUSTE\",\"quantidade\":0}")) {
            String deB = postAs(donoA, "/me/inventory/" + itemB.getId() + "/movements", body)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("ITEM_NAO_ENCONTRADO"))
                    .andExpect(jsonPath("$.message").isString())
                    .andReturn().getResponse().getContentAsString();
            String inexistente = postAs(donoA, "/me/inventory/" + INEXISTENTE + "/movements", body)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(deB).isEqualTo(inexistente).doesNotContain(SEGREDO).doesNotContain("4.37");
        }
        String deB = getAs(donoA, "/me/inventory/" + itemB.getId() + "/movements")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ITEM_NAO_ENCONTRADO"))
                .andReturn().getResponse().getContentAsString();
        assertThat(deB).isEqualTo(getAs(donoA, "/me/inventory/" + INEXISTENTE + "/movements")
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());

        InventoryItem depois = items.findById(itemB.getId()).orElseThrow();
        assertThat(depois.getQuantidade()).isEqualTo(10);
        assertThat(depois.getCustoUnitario()).isEqualByComparingTo("4.37");
        assertThat(movements.countByItemId(itemB.getId())).isZero();
    }

    @Test
    void historicoNaoMostraMovimentosDeOutraEmpresa() throws Exception {
        String donoA = dono(empresaA);
        String donoB = dono(empresaB);
        postAs(donoB, "/me/inventory/" + itemB.getId() + "/movements", "{\"tipo\":\"SAIDA\",\"quantidade\":1}")
                .andExpect(status().isCreated());
        postAs(donoA, "/me/inventory/" + itemA.getId() + "/movements", "{\"tipo\":\"SAIDA\",\"quantidade\":2}")
                .andExpect(status().isCreated());

        getAs(donoA, "/me/inventory/" + itemA.getId() + "/movements")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].item_id").value(itemA.getId()))
                .andExpect(jsonPath("$[0].quantidade").value(2));
    }

    @Test
    void semGerenciarEstoqueDa403SemEfeito() throws Exception {
        String leitura = membro(empresaA, "leitura", "LEITURA", null, "ATIVO", List.of(Permissoes.VER_FINANCEIRO));

        postAs(leitura, "/me/inventory/" + itemA.getId() + "/movements", "{\"tipo\":\"ENTRADA\",\"quantidade\":5}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SEM_PERMISSAO"))
                .andExpect(jsonPath("$.permissao").value("gerenciar_estoque"));
        // LEITURA ainda vê o histórico (ver_financeiro).
        getAs(leitura, "/me/inventory/" + itemA.getId() + "/movements").andExpect(status().isOk());

        assertThat(items.findById(itemA.getId()).orElseThrow().getQuantidade()).isEqualTo(10);
        assertThat(movements.countByItemId(itemA.getId())).isZero();
    }
}
