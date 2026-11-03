package com.erpbanking.transactions.portal.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.portal.CurrentCustomer;
import com.erpbanking.transactions.portal.Membro;
import com.erpbanking.transactions.portal.OnboardingClient;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.PortalExceptions.BusinessRuleException;
import com.erpbanking.transactions.portal.PortalMetrics;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/** Pagamento acima do limite do membro, bandeja de aprovação, aprovar e rejeitar (resolver real, Onboarding mockado). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApprovalMvcTest {

    private static final AtomicLong IDS = new AtomicLong(960_000);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired PayableRepository payables;
    @Autowired PaymentRepository payments;
    @Autowired TransactionRepository transactions;
    @Autowired PortalPaymentService service;
    @Autowired MeterRegistry registry;
    @MockBean OnboardingClient onboarding;

    Long customerId;
    Account account;

    @BeforeEach
    void setUp() {
        customerId = IDS.incrementAndGet();
    }

    private String sub(String who) {
        return "auth0|" + customerId + "-" + who;
    }

    private static MockHttpServletRequestBuilder as(String sub, MockHttpServletRequestBuilder b) {
        return b.with(jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF),
                new SimpleGrantedAuthority(SecurityConfig.WRITE_SELF)));
    }

    /** Próximas resoluções do GET /me devolvem este membro (o cache é por sub, então cada papel usa um sub). */
    private void membro(String papel, String limite, String nome, String... permissoes) {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(customerId, "APROVADO",
                new OnboardingClient.MembroResponse(1L, papel, List.of(permissoes),
                        limite == null ? null : new BigDecimal(limite), "ATIVO", nome), null));
    }

    private String operador() {
        membro("OPERADOR", "500.00", "Otávio", Permissoes.VER_FINANCEIRO, Permissoes.PAGAR);
        return sub("operador");
    }

    private String financeiro() {
        membro("FINANCEIRO", null, "Fernanda", Permissoes.VER_FINANCEIRO, Permissoes.PAGAR,
                Permissoes.APROVAR_PAGAMENTOS);
        return sub("financeiro");
    }

    private void account(String saldo) {
        account = accounts.save(new Account(customerId, new BigDecimal(saldo)));
    }

    private Payable payable(String valor) {
        Payable p = new Payable();
        p.setCustomerId(customerId);
        p.setFornecedor("Fornecedor X");
        p.setCategoria(PayableCategory.FORNECEDOR);
        p.setVencimento(LocalDate.now().plusDays(3));
        p.setValor(new BigDecimal(valor));
        p.setStatus(PayableStatus.ABERTO);
        return payables.save(p);
    }

    private ResultActions postJson(String sub, String path, String body) throws Exception {
        var b = as(sub, post(path));
        return mockMvc.perform(body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions pay(String sub, String valor, Long payableId, LocalDate agendadoPara) throws Exception {
        return postJson(sub, "/me/payments", "{\"tipo\":\"BOLETO\",\"valor\":" + valor + ",\"destinatario\":\"Forn\""
                + (payableId == null ? "" : ",\"payable_id\":" + payableId)
                + (agendadoPara == null ? "" : ",\"agendado_para\":\"" + agendadoPara + "\"") + "}");
    }

    private long id(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private double paymentsCounter(String status) {
        return registry.get(PortalMetrics.PAYMENTS).tags("tipo", "BOLETO", "status", status).counter().count();
    }

    private BigDecimal saldo() {
        return accounts.findById(account.getId()).orElseThrow().getSaldo();
    }

    @Test
    void operadorAcimaDoLimiteFicaPendenteEAprovacaoDebita() throws Exception {
        account("1000.00");
        Payable conta = payable("600.00");
        double pendentesAntes = paymentsCounter("PENDENTE_APROVACAO");
        String operador = operador();

        long pendente = id(pay(operador, "600.00", conta.getId(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDENTE_APROVACAO"))
                .andExpect(jsonPath("$.saldo_apos").value(1000.0)));
        assertThat(saldo()).isEqualByComparingTo("1000.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(account.getId())).isEmpty();
        assertThat(payables.findById(conta.getId()).orElseThrow().getStatus()).isEqualTo(PayableStatus.ABERTO);
        assertThat(paymentsCounter("PENDENTE_APROVACAO")).isEqualTo(pendentesAntes + 1);

        pay(operador, "400.00", null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFETIVADO"));
        pay(operador, "600.00", conta.getId(), null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PAGAMENTO_PENDENTE"));
        mockMvc.perform(as(operador, get("/me/payments/pending"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SEM_PERMISSAO"))
                .andExpect(jsonPath("$.permissao").value("aprovar_pagamentos"));
        postJson(operador, "/me/payments/" + pendente + "/approve", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.permissao").value("aprovar_pagamentos"));

        String financeiro = financeiro();
        mockMvc.perform(as(financeiro, get("/me/payments/pending")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(pendente))
                .andExpect(jsonPath("$[0].status").value("PENDENTE_APROVACAO"))
                .andExpect(jsonPath("$[0].created_by_nome").value("Otávio"));

        postJson(financeiro, "/me/payments/" + pendente + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pendente))
                .andExpect(jsonPath("$.status").value("EFETIVADO"))
                .andExpect(jsonPath("$.saldo_apos").value(0.0))
                .andExpect(jsonPath("$.comprovante.conta_origem.id").value(account.getId()));
        assertThat(saldo()).isEqualByComparingTo("0.00");
        Payable paga = payables.findById(conta.getId()).orElseThrow();
        assertThat(paga.getStatus()).isEqualTo(PayableStatus.PAGO);
        assertThat(paga.getPagoEm()).isNotNull();
        Payment aprovado = payments.findById(pendente).orElseThrow();
        assertThat(aprovado.getApprovedBySub()).isEqualTo(financeiro);
        assertThat(aprovado.getTransactionId()).isNotNull();

        mockMvc.perform(as(financeiro, get("/me/payments")))
                .andExpect(jsonPath("$[?(@.id == " + pendente + ")].approved_by_nome").value("Fernanda"))
                .andExpect(jsonPath("$[?(@.id == " + pendente + ")].approved_at").isNotEmpty());
        mockMvc.perform(as(financeiro, get("/me/payments/pending"))).andExpect(jsonPath("$.length()").value(0));
        postJson(financeiro, "/me/payments/" + pendente + "/approve", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PAGAMENTO_NAO_PENDENTE"));
    }

    @Test
    void autorNaoAprovaNemRejeitaOProprioPagamento() throws Exception {
        account("1000.00");
        membro("FINANCEIRO", "100.00", "Fábio", Permissoes.PAGAR, Permissoes.APROVAR_PAGAMENTOS);
        String autor = sub("autor");
        long pendente = id(pay(autor, "200.00", null, null).andExpect(jsonPath("$.status").value("PENDENTE_APROVACAO")));

        for (String acao : List.of("approve", "reject")) {
            postJson(autor, "/me/payments/" + pendente + "/" + acao, null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("SEM_PERMISSAO"))
                    .andExpect(jsonPath("$.permissao").value("aprovar_pagamentos"))
                    .andExpect(jsonPath("$.motivo").value("AUTOR"));
        }
        assertThat(payments.findById(pendente).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDENTE_APROVACAO);
    }

    @Test
    void rejeitarNaoDebitaELiberaAContaAPagar() throws Exception {
        account("1000.00");
        Payable conta = payable("700.00");
        double rejeitadosAntes = paymentsCounter("REJEITADO");
        String operador = operador();
        long pendente = id(pay(operador, "700.00", conta.getId(), null));
        long outro = id(pay(operador, "800.00", null, null));

        String financeiro = financeiro();
        postJson(financeiro, "/me/payments/" + pendente + "/reject", "{\"motivo\":\" Sem nota fiscal \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJEITADO"))
                .andExpect(jsonPath("$.rejected_by_nome").value("Fernanda"))
                .andExpect(jsonPath("$.rejected_at").isNotEmpty())
                .andExpect(jsonPath("$.motivo_rejeicao").value("Sem nota fiscal"))
                .andExpect(jsonPath("$.created_by_nome").value("Otávio"));
        postJson(financeiro, "/me/payments/" + outro + "/reject", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.motivo_rejeicao").doesNotExist());
        postJson(financeiro, "/me/payments/" + outro + "/reject", "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PAGAMENTO_NAO_PENDENTE"));
        assertThat(saldo()).isEqualByComparingTo("1000.00");
        assertThat(paymentsCounter("REJEITADO")).isEqualTo(rejeitadosAntes + 2);

        pay(financeiro, "700.00", conta.getId(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFETIVADO"));
    }

    @Test
    void aprovarComDataFuturaAgendaSemDebitar() throws Exception {
        account("10.00");
        Payable conta = payable("600.00");
        LocalDate data = LocalDate.now(PortalPaymentService.ZONE).plusDays(5);
        long pendente = id(pay(operador(), "600.00", conta.getId(), data)
                .andExpect(jsonPath("$.status").value("PENDENTE_APROVACAO"))
                .andExpect(jsonPath("$.comprovante.agendado_para").value(data.toString())));

        postJson(financeiro(), "/me/payments/" + pendente + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGENDADO"))
                .andExpect(jsonPath("$.saldo_apos").value(10.0))
                .andExpect(jsonPath("$.comprovante.agendado_para").value(data.toString()));
        assertThat(saldo()).isEqualByComparingTo("10.00");
        assertThat(payables.findById(conta.getId()).orElseThrow().getStatus()).isEqualTo(PayableStatus.AGENDADO);
        assertThat(service.dueScheduledIds(data)).contains(pendente);
    }

    @Test
    void aprovarSemSaldoDa422EContinuaPendente() throws Exception {
        account("100.00");
        long pendente = id(pay(operador(), "600.00", null, null));

        postJson(financeiro(), "/me/payments/" + pendente + "/approve", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SALDO_INSUFICIENTE"))
                .andExpect(jsonPath("$.faltam").value(500.0));
        assertThat(payments.findById(pendente).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDENTE_APROVACAO);
        assertThat(saldo()).isEqualByComparingTo("100.00");
    }

    @Test
    void aprovarContaAPagarQueJaFoiPagaDa422() throws Exception {
        account("1000.00");
        Payable conta = payable("600.00");
        long pendente = id(pay(operador(), "600.00", conta.getId(), null));
        conta = payables.findById(conta.getId()).orElseThrow();
        conta.setStatus(PayableStatus.PAGO);
        payables.save(conta);

        postJson(financeiro(), "/me/payments/" + pendente + "/approve", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CONTA_JA_PAGA"));
    }

    @Test
    void pagamentoDeOutraEmpresaDa404() throws Exception {
        account("1000.00");
        long pendente = id(pay(operador(), "600.00", null, null));

        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(customerId + 1_000_000, "APROVADO",
                new OnboardingClient.MembroResponse(9L, "DONO", List.copyOf(Permissoes.TODAS), null, "ATIVO", "X"),
                null));
        String outra = sub("outra-empresa");
        for (String acao : List.of("approve", "reject")) {
            postJson(outra, "/me/payments/" + pendente + "/" + acao, null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("PAGAMENTO_NAO_ENCONTRADO"));
        }
    }

    @Test
    void semMembroEhDonoSemLimite() throws Exception {
        account("100000.00");
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(customerId, "APROVADO"));
        pay(sub("dono"), "99999.00", null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFETIVADO"));
    }

    @Test
    void aprovacoesConcorrentesDebitamUmaVezSo() throws Exception {
        account("1000.00");
        Payable conta = payable("600.00");
        long pendente = id(pay(operador(), "600.00", conta.getId(), null));

        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CurrentCustomer aprovador = new CurrentCustomer("auth0|aprovador-" + i, customerId, "APROVADO",
                    new Membro(10L + i, "FINANCEIRO", Permissoes.TODAS, null, "ATIVO", "Aprovador " + i));
            Callable<Object> task = () -> {
                start.await();
                return service.approve(aprovador, pendente);
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            try {
                results.add(f.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                results.add(e.getCause());
            }
        }
        pool.shutdownNow();

        assertThat(results).filteredOn(PaymentDtos.PaymentCreated.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(BusinessRuleException.class::isInstance).hasSize(n - 1)
                .allSatisfy(e -> assertThat(((BusinessRuleException) e).getCode()).isEqualTo("PAGAMENTO_NAO_PENDENTE"));
        assertThat(saldo()).isEqualByComparingTo("400.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(account.getId())).hasSize(1);
        assertThat(payments.findById(pendente).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EFETIVADO);
    }
}
