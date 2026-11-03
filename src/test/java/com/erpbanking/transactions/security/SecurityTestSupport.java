package com.erpbanking.transactions.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.erpbanking.transactions.config.SecurityConfig;
import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.CreditRequest;
import com.erpbanking.transactions.domain.CreditSimulation;
import com.erpbanking.transactions.domain.CreditStatus;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.PaymentType;
import com.erpbanking.transactions.domain.Receivable;
import com.erpbanking.transactions.domain.ReceivableStatus;
import com.erpbanking.transactions.portal.OnboardingClient;
import com.erpbanking.transactions.portal.OnboardingClient.MeResponse;
import com.erpbanking.transactions.portal.OnboardingClient.MembroResponse;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient.ScoreAnalysis;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.CreditRequestRepository;
import com.erpbanking.transactions.repository.CreditSimulationRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.repository.ReceivableRepository;
import com.erpbanking.transactions.repository.TransactionRepository;

/**
 * Base dos testes de segurança: duas empresas no H2 e o Onboarding {@code GET /me} mockado por {@code sub} (cada
 * token resolve para a empresa e o membro cadastrados em {@link #perfil}). Mesmos MockBeans do
 * PortalPermissionsMvcTest, para reaproveitar o contexto Spring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class SecurityTestSupport {

    private static final AtomicLong IDS = new AtomicLong(7_000_000);

    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accounts;
    @Autowired PaymentRepository payments;
    @Autowired PayableRepository payables;
    @Autowired ReceivableRepository receivables;
    @Autowired CreditSimulationRepository simulations;
    @Autowired CreditRequestRepository creditRequests;
    @Autowired TransactionRepository transactions;
    @MockBean OnboardingClient onboarding;
    @MockBean ScoreAnalysisClient scoreAnalysis;

    /** Resposta do GET /me por sub; um Supplier que lança simula o 403 do Onboarding. */
    final Map<String, Supplier<MeResponse>> perfis = new ConcurrentHashMap<>();

    Long empresaA;
    Long empresaB;

    @BeforeEach
    void setUpEmpresas() {
        empresaA = IDS.incrementAndGet();
        empresaB = IDS.incrementAndGet();
        when(scoreAnalysis.myAnalysis()).thenReturn(Optional.of(new ScoreAnalysis(742.0, "MEDIO", "APROVADO")));
        when(onboarding.me()).thenAnswer(inv -> {
            String sub = SecurityContextHolder.getContext().getAuthentication().getName();
            Supplier<MeResponse> p = perfis.get(sub);
            if (p == null) {
                throw new IllegalStateException("sub sem perfil no teste: " + sub);
            }
            return p.get();
        });
    }

    /** Sub único por teste (o cache do resolver é por sub e dura 60 s). */
    String sub(Long empresa, String quem) {
        return "auth0|" + empresa + "-" + quem;
    }

    /** DONO com todas as permissões, sem limite. */
    String dono(Long empresa) {
        return membro(empresa, "dono", "DONO", null, "ATIVO", List.copyOf(Permissoes.TODAS));
    }

    String membro(Long empresa, String quem, String papel, String limite, String status, List<String> permissoes) {
        String sub = sub(empresa, quem);
        MeResponse me = new MeResponse(empresa, "APROVADO", new MembroResponse(1L, papel, permissoes,
                limite == null ? null : new BigDecimal(limite), status, quem), null);
        perfis.put(sub, () -> me);
        return sub;
    }

    static MockHttpServletRequestBuilder cliente(String sub, MockHttpServletRequestBuilder b) {
        return b.with(jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF),
                new SimpleGrantedAuthority(SecurityConfig.WRITE_SELF)));
    }

    ResultActions getAs(String sub, String path) throws Exception {
        return mockMvc.perform(cliente(sub, get(path)));
    }

    ResultActions postAs(String sub, String path, String body) throws Exception {
        MockHttpServletRequestBuilder b = cliente(sub, post(path));
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(b);
    }

    // ---- dados no H2 ----

    Account conta(Long empresa, String saldo) {
        return accounts.save(new Account(empresa, new BigDecimal(saldo)));
    }

    BigDecimal saldo(Account a) {
        return accounts.findById(a.getId()).orElseThrow().getSaldo();
    }

    Payable contaAPagar(Long empresa, String fornecedor, String valor) {
        Payable p = new Payable();
        p.setCustomerId(empresa);
        p.setFornecedor(fornecedor);
        p.setCategoria(PayableCategory.FORNECEDOR);
        p.setVencimento(LocalDate.now().plusDays(3));
        p.setValor(new BigDecimal(valor));
        p.setStatus(PayableStatus.ABERTO);
        return payables.save(p);
    }

    Payment pagamentoPendente(Long empresa, String destinatario, String valor, Long payableId, String autorSub) {
        Payment p = new Payment();
        p.setCustomerId(empresa);
        p.setTipo(PaymentType.PIX);
        p.setValor(new BigDecimal(valor));
        p.setDestinatario(destinatario);
        p.setPayableId(payableId);
        p.setStatus(PaymentStatus.PENDENTE_APROVACAO);
        p.setAutenticacao("AUT-" + empresa + "-" + System.nanoTime());
        p.setCreatedBySub(autorSub);
        return payments.save(p);
    }

    Receivable recebivel(Long empresa, String liquido) {
        Receivable r = new Receivable();
        r.setCustomerId(empresa);
        r.setVencimento(LocalDate.now().plusDays(20));
        r.setValorBruto(new BigDecimal(liquido));
        r.setTaxaAdquirente(BigDecimal.ZERO);
        r.setValorLiquido(new BigDecimal(liquido));
        r.setAdquirente("Cielo");
        r.setStatus(ReceivableStatus.PREVISTO);
        return receivables.save(r);
    }

    CreditSimulation simulacao(Long empresa, String valor, Long creditRequestId) {
        CreditSimulation s = new CreditSimulation();
        s.setCustomerId(empresa);
        s.setProduto("CAPITAL_DE_GIRO");
        s.setValor(new BigDecimal(valor));
        s.setPrazoMeses(6);
        s.setTaxaMensal(new BigDecimal("2.4900"));
        s.setFaixaRisco("MEDIO");
        s.setScore(742.0);
        s.setAprovacao("PRE_APROVADO");
        s.setLimite(new BigDecimal("100000.00"));
        s.setValidade(LocalDate.now().plusDays(7));
        s.setCreditRequestId(creditRequestId);
        s.setCriadoEm(Instant.now());
        return simulations.save(s);
    }

    CreditRequest pedidoAguardando(Long empresa, String valor) {
        CreditRequest r = new CreditRequest();
        r.setCustomerId(empresa);
        r.setValorSolicitado(new BigDecimal(valor));
        r.setPrazoMeses(6);
        r.setStatus(CreditStatus.AGUARDANDO_CONTRATACAO);
        r.setCriadoEm(Instant.now());
        CreditRequest saved = creditRequests.save(r);
        simulacao(empresa, valor, saved.getId());
        return saved;
    }

    long simulacoesDa(Long empresa) {
        return simulations.findAll().stream().filter(s -> empresa.equals(s.getCustomerId())).count();
    }

    long pedidosDa(Long empresa) {
        return creditRequests.findByCustomerIdOrderByIdDesc(empresa).size();
    }

    long pagamentosDa(Long empresa) {
        return payments.findByCustomerIdOrderByIdDesc(empresa).size();
    }
}
