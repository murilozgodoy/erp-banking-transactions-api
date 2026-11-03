package com.erpbanking.transactions.portal.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.erpbanking.transactions.config.SecurityConfig;
import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.OnboardingPendingException;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.TransactionRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MePaymentsControllerTest {

    private static final AtomicLong IDS = new AtomicLong(900_000);

    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accounts;
    @Autowired PayableRepository payables;
    @Autowired TransactionRepository transactions;
    @MockBean CurrentCustomerResolver resolver;

    Long customerId;

    @BeforeEach
    void setUp() {
        customerId = IDS.incrementAndGet();
        when(resolver.requireCustomerId()).thenReturn(customerId);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF),
                new SimpleGrantedAuthority(SecurityConfig.WRITE_SELF)));
    }

    private Account account(String saldo) {
        return accounts.save(new Account(customerId, new BigDecimal(saldo)));
    }

    private Payable payable(Long owner, PayableStatus status) {
        Payable p = new Payable();
        p.setCustomerId(owner);
        p.setFornecedor("Fornecedor X");
        p.setCategoria(PayableCategory.FORNECEDOR);
        p.setVencimento(LocalDate.now().plusDays(3));
        p.setValor(new BigDecimal("150.00"));
        p.setStatus(status);
        p.setCodigoBarras("23790000000000000000000000000000000000000000");
        return payables.save(p);
    }

    private org.springframework.test.web.servlet.ResultActions postPayment(String json) throws Exception {
        return mockMvc.perform(authed(post("/me/payments")).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void pagamentoImediatoDebitaContaEMarcaContaComoPaga() throws Exception {
        Account acc = account("1000.00");
        Payable payable = payable(customerId, PayableStatus.ABERTO);

        postPayment("{\"tipo\":\"BOLETO\",\"valor\":150.00,\"destinatario\":\"Fornecedor X\","
                + "\"descricao\":\"NF 123\",\"payable_id\":" + payable.getId() + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("EFETIVADO"))
                .andExpect(jsonPath("$.autenticacao").value(matchesPattern("([0-9A-F]{4}-){7}[0-9A-F]{4}")))
                .andExpect(jsonPath("$.saldo_apos").value(850.00))
                .andExpect(jsonPath("$.comprovante.tipo").value("BOLETO"))
                .andExpect(jsonPath("$.comprovante.valor").value(150.00))
                .andExpect(jsonPath("$.comprovante.destinatario").value("Fornecedor X"))
                .andExpect(jsonPath("$.comprovante.descricao").value("NF 123"))
                .andExpect(jsonPath("$.comprovante.data_hora").exists())
                .andExpect(jsonPath("$.comprovante.conta_origem.id").value(acc.getId()))
                .andExpect(jsonPath("$.comprovante.conta_origem.agencia").value("0001"))
                .andExpect(jsonPath("$.comprovante.conta_origem.numero").value(matchesPattern("[0-9]{6}-[0-9]")))
                .andExpect(jsonPath("$.comprovante.codigo_barras").value(payable.getCodigoBarras()));

        assertThat(accounts.findById(acc.getId()).orElseThrow().getSaldo()).isEqualByComparingTo("850.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(acc.getId())).hasSize(1);
        Payable after = payables.findById(payable.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PayableStatus.PAGO);
        assertThat(after.getPagoEm()).isNotNull();

        mockMvc.perform(authed(get("/me/payments")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payable_id").value(payable.getId()))
                .andExpect(jsonPath("$[0].status").value("EFETIVADO"))
                .andExpect(jsonPath("$[0].criado_em").exists());
    }

    @Test
    void pagamentoAgendadoNaoDebitaEAgendaConta() throws Exception {
        Account acc = account("10.00");
        Payable payable = payable(customerId, PayableStatus.ABERTO);
        String data = LocalDate.now().plusDays(5).toString();

        postPayment("{\"tipo\":\"PIX\",\"valor\":150.00,\"destinatario\":\"Fornecedor X\",\"payable_id\":"
                + payable.getId() + ",\"agendado_para\":\"" + data + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGENDADO"))
                .andExpect(jsonPath("$.saldo_apos").value(10.00))
                .andExpect(jsonPath("$.comprovante.agendado_para").value(data));

        assertThat(accounts.findById(acc.getId()).orElseThrow().getSaldo()).isEqualByComparingTo("10.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(acc.getId())).isEmpty();
        assertThat(payables.findById(payable.getId()).orElseThrow().getStatus()).isEqualTo(PayableStatus.AGENDADO);
    }

    @Test
    void saldoInsuficienteDevolve422ComFaltam() throws Exception {
        account("100.00");
        postPayment("{\"tipo\":\"TED\",\"valor\":250.50,\"destinatario\":\"Banco Y\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SALDO_INSUFICIENTE"))
                .andExpect(jsonPath("$.faltam").value(150.50));
    }

    @Test
    void semContaAbreUmaEFalhaPorSaldo() throws Exception {
        postPayment("{\"tipo\":\"PIX\",\"valor\":10,\"destinatario\":\"Maria\",\"agendado_para\":\""
                + LocalDate.now().minusDays(1) + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.faltam").value(10));
        assertThat(accounts.findByCustomerIdOrderByIdDesc(customerId)).hasSize(1);
    }

    @Test
    void contaAPagarDeOutroClienteDevolve404() throws Exception {
        account("1000.00");
        Payable other = payable(customerId + 1_000_000, PayableStatus.ABERTO);
        postPayment("{\"tipo\":\"BOLETO\",\"valor\":150,\"destinatario\":\"X\",\"payable_id\":" + other.getId() + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONTA_A_PAGAR_NAO_ENCONTRADA"));
    }

    @Test
    void contaJaPagaDevolve422() throws Exception {
        account("1000.00");
        Payable paga = payable(customerId, PayableStatus.PAGO);
        postPayment("{\"tipo\":\"BOLETO\",\"valor\":150,\"destinatario\":\"X\",\"payable_id\":" + paga.getId() + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CONTA_JA_PAGA"));
    }

    @Test
    void validacaoDevolve400() throws Exception {
        postPayment("{\"tipo\":\"PIX\",\"valor\":-1,\"destinatario\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.campos.valor").exists())
                .andExpect(jsonPath("$.campos.destinatario").exists());
        postPayment("{\"tipo\":\"DOC\",\"valor\":1,\"destinatario\":\"a\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void onboardingPendenteDevolve409() throws Exception {
        when(resolver.requireCustomerId()).thenThrow(new OnboardingPendingException("EM_ANALISE"));
        mockMvc.perform(authed(get("/me/payments")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ONBOARDING_PENDENTE"));
    }

    @Test
    void autenticacaoEhUnica() {
        assertThat(PortalPaymentService.newAuthentication()).isNotEqualTo(PortalPaymentService.newAuthentication());
    }
}
