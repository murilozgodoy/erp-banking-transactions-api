package com.erpbanking.transactions.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;

import com.erpbanking.transactions.config.SecurityConfig;
import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditSimulation;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient.ScoreAnalysis;

/**
 * IDOR entre empresas: um usuário da empresa A nunca lê nem altera um recurso da empresa B e não descobre que ele
 * existe (404 igual ao de um id inexistente, nunca 403), sem efeito colateral no banco.
 */
class TenantIsolationMvcTest extends SecurityTestSupport {

    private static final String SEGREDO = "SEGREDO-EMPRESA-B";
    private static final long INEXISTENTE = 987_654_321_000L;

    String donoA;
    Account contaA;
    Account contaB;
    Payable contaAPagarB;
    Payment pendenteB;
    Receivable recebivelB;
    CreditSimulation simulacaoB;
    CreditRequest pedidoB;

    @BeforeEach
    void cenario() {
        donoA = dono(empresaA);
        contaA = conta(empresaA, "5000.00");
        contaB = conta(empresaB, "5000.00");
        contaAPagarB = contaAPagar(empresaB, SEGREDO, "321.00");
        pendenteB = pagamentoPendente(empresaB, SEGREDO, "777.13", null, sub(empresaB, "operador"));
        recebivelB = recebivel(empresaB, "4321.19");
        simulacaoB = simulacao(empresaB, "1234.57", null);
        pedidoB = pedidoAguardando(empresaB, "2222.31");
    }

    /**
     * 404 com o mesmo código para o id de B e para um id inexistente, sem nenhum dado de B no corpo. {@code __ID__}
     * na rota ou no corpo é trocado pelo id.
     */
    private void notFoundIgualAoInexistente(String rota, String body, long idB, String code) throws Exception {
        String b = postAs(donoA, rota.replace("__ID__", String.valueOf(idB)), body.replace("__ID__", String.valueOf(idB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(code)).andReturn().getResponse().getContentAsString();
        String inexistente = String.valueOf(INEXISTENTE);
        String n = postAs(donoA, rota.replace("__ID__", inexistente), body.replace("__ID__", inexistente))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(code))
                .andReturn().getResponse().getContentAsString();
        // Mesma resposta, só muda o id ecoado: não dá para saber se o recurso de B existe.
        assertThat(b.replace(String.valueOf(idB), "ID")).isEqualTo(n.replace(inexistente, "ID"));
        assertThat(b).doesNotContain(SEGREDO).doesNotContain("777.13").doesNotContain("1234.57").doesNotContain("4321.19");
    }

    @Test
    void aprovarERejeitarPagamentoDeOutraEmpresaDa404SemEfeito() throws Exception {
        for (String acao : List.of("approve", "reject")) {
            String body = "reject".equals(acao) ? "{\"motivo\":\"x\"}" : "{}";
            notFoundIgualAoInexistente("/me/payments/__ID__/" + acao, body, pendenteB.getId(),
                    "PAGAMENTO_NAO_ENCONTRADO");
        }
        Payment depois = payments.findById(pendenteB.getId()).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(PaymentStatus.PENDENTE_APROVACAO);
        assertThat(depois.getApprovedBySub()).isNull();
        assertThat(depois.getRejectedBySub()).isNull();
        assertThat(depois.getTransactionId()).isNull();
        assertThat(saldo(contaA)).isEqualByComparingTo("5000.00");
        assertThat(saldo(contaB)).isEqualByComparingTo("5000.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(contaA.getId())).isEmpty();
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(contaB.getId())).isEmpty();
    }

    @Test
    void pagarContaAPagarDeOutraEmpresaDa404SemEfeito() throws Exception {
        String body = "{\"tipo\":\"BOLETO\",\"valor\":321.00,\"destinatario\":\"X\",\"payable_id\":__ID__}";
        notFoundIgualAoInexistente("/me/payments", body, contaAPagarB.getId(), "CONTA_A_PAGAR_NAO_ENCONTRADA");
        // Com agendamento também (outro ramo do serviço).
        postAs(donoA, "/me/payments", "{\"tipo\":\"BOLETO\",\"valor\":321.00,\"destinatario\":\"X\",\"payable_id\":"
                + contaAPagarB.getId() + ",\"agendado_para\":\"" + java.time.LocalDate.now().plusDays(10) + "\"}")
                .andExpect(status().isNotFound());
        assertThat(payables.findById(contaAPagarB.getId()).orElseThrow().getStatus()).isEqualTo(PayableStatus.ABERTO);
        assertThat(pagamentosDa(empresaA)).isZero();
        assertThat(pagamentosDa(empresaB)).isEqualTo(1);
        assertThat(saldo(contaA)).isEqualByComparingTo("5000.00");
        assertThat(accounts.findIdsByCustomerId(empresaA)).hasSize(1);
    }

    @Test
    void pedirCreditoComSimulacaoDeOutraEmpresaDa404SemEfeito() throws Exception {
        notFoundIgualAoInexistente("/me/credit/requests", "{\"simulation_id\":__ID__}", simulacaoB.getId(),
                "SIMULACAO_NAO_ENCONTRADA");
        assertThat(simulations.findById(simulacaoB.getId()).orElseThrow().getCreditRequestId()).isNull();
        assertThat(pedidosDa(empresaA)).isZero();
        assertThat(simulacoesDa(empresaA)).isZero();
    }

    @Test
    void confirmarPedidoDeOutraEmpresaDa404SemEfeito() throws Exception {
        notFoundIgualAoInexistente("/me/credit/requests/__ID__/confirm", "{}", pedidoB.getId(),
                "PEDIDO_NAO_ENCONTRADO");
        assertThat(creditRequests.findById(pedidoB.getId()).orElseThrow().getStatus())
                .isEqualTo(CreditStatus.AGUARDANDO_CONTRATACAO);
        assertThat(simulacoesDa(empresaA)).isZero();
    }

    @Test
    void simularComRecebivelDeOutraEmpresaDa404SemEfeito() throws Exception {
        Receivable meu = recebivel(empresaA, "1000.00");
        String body = "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":500,\"prazo_meses\":3,\"receivable_ids\":["
                + meu.getId() + ",__ID__]}";
        notFoundIgualAoInexistente("/me/credit/simulations", body, recebivelB.getId(), "RECEBIVEL_NAO_ENCONTRADO");
        assertThat(simulacoesDa(empresaA)).isZero();
        assertThat(receivables.findById(recebivelB.getId()).orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.PREVISTO);
    }

    /** Corrigido: com crédito negado (MUITO_ALTO) o limite não era calculado e os ids de B passavam sem 404. */
    @Test
    void recebivelDeOutraEmpresaDa404MesmoComCreditoNegado() throws Exception {
        Mockito.when(scoreAnalysis.myAnalysis())
                .thenReturn(Optional.of(new ScoreAnalysis(200.0, "MUITO_ALTO", "APROVADO")));
        postAs(donoA, "/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":500,"
                + "\"prazo_meses\":3,\"receivable_ids\":[" + recebivelB.getId() + "]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECEBIVEL_NAO_ENCONTRADO"));
        assertThat(simulacoesDa(empresaA)).isZero();
    }

    @Test
    void recebiveisDuplicadosSaoDeduplicados() throws Exception {
        Receivable meu = recebivel(empresaA, "1000.00");
        postAs(donoA, "/me/credit/simulations", "{\"produto\":\"ANTECIPACAO_RECEBIVEIS\",\"valor\":500,"
                + "\"prazo_meses\":3,\"receivable_ids\":[" + meu.getId() + "," + meu.getId() + "," + meu.getId() + "]}")
                .andExpect(status().isCreated())
                // 80% de 1000 (uma vez só, não 3×).
                .andExpect(jsonPath("$.limite_disponivel").value(800.00));
        assertThat(simulations.findAll().stream().filter(s -> empresaA.equals(s.getCustomerId()))
                .map(CreditSimulation::getReceivableIds)).containsExactly(String.valueOf(meu.getId()));
    }

    @Test
    void listasNuncaTrazemLinhasDeOutraEmpresa() throws Exception {
        for (String rota : List.of("/me/payments", "/me/payments/pending", "/me/credit/requests", "/me/credit/offers",
                "/me/overview", "/me/sales", "/me/receivables", "/me/payables", "/me/statement", "/me/inventory",
                "/me/pendencias")) {
            String body = getAs(donoA, rota).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            assertThat(body).as(rota).doesNotContain(SEGREDO).doesNotContain("4321.19").doesNotContain("2222.31")
                    .doesNotContain("777.13");
        }
        getAs(donoA, "/me/payments").andExpect(jsonPath("$.length()").value(0));
        getAs(donoA, "/me/payments/pending").andExpect(jsonPath("$.length()").value(0));
        getAs(donoA, "/me/credit/requests").andExpect(jsonPath("$.length()").value(0));
        getAs(donoA, "/me/receivables").andExpect(jsonPath("$.itens.length()").value(0));
        getAs(donoA, "/me/payables").andExpect(jsonPath("$.itens.length()").value(0));
    }

    // ---- rotas de staff: o token de cliente (read:self/write:self) recebe 403 ----

    @Test
    void tokenDeClienteNaoAcessaRotasDeStaff() throws Exception {
        String saldoB = saldo(contaB).toPlainString();
        for (String rota : List.of("/accounts", "/accounts/" + contaB.getId(),
                "/accounts/" + contaB.getId() + "/transactions", "/credits", "/credits/" + pedidoB.getId())) {
            getAs(donoA, rota).andExpect(status().isForbidden());
        }
        postAs(donoA, "/transactions", "{\"account_id\":" + contaB.getId() + ",\"type\":\"DEBIT\",\"amount\":100}")
                .andExpect(status().isForbidden());
        postAs(donoA, "/accounts", "{\"customer_id\":" + empresaA + "}").andExpect(status().isForbidden());
        postAs(donoA, "/credits", "{\"customer_id\":" + empresaB + ",\"valor_solicitado\":100,\"prazo_meses\":3}")
                .andExpect(status().isForbidden());
        assertThat(saldo(contaB)).isEqualByComparingTo(saldoB);
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(contaB.getId())).isEmpty();
        assertThat(pedidosDa(empresaB)).isEqualTo(1);
    }

    @Test
    void staffComPermissaoDeStaffAcessa() throws Exception {
        ResultActions r = mockMvc.perform(get("/accounts/" + contaB.getId()).with(jwt().authorities(
                new SimpleGrantedAuthority(SecurityConfig.READ_ACCOUNTS))));
        r.andExpect(status().isOk());
        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.READ_ACCOUNTS))))
                .andExpect(status().isForbidden());
    }
}
