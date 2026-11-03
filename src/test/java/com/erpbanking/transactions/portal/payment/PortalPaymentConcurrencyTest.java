package com.erpbanking.transactions.portal.payment;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.ActiveProfiles;

import com.erpbanking.transactions.portal.CurrentCustomer;
import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableCategory;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.PaymentType;
import com.erpbanking.transactions.portal.CurrentCustomerResolver;
import com.erpbanking.transactions.portal.PortalExceptions.BusinessRuleException;
import com.erpbanking.transactions.portal.PortalExceptions.SaldoInsuficienteException;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentCreated;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentRequest;
import com.erpbanking.transactions.repository.AccountRepository;
import com.erpbanking.transactions.repository.PayableRepository;
import com.erpbanking.transactions.repository.PaymentRepository;
import com.erpbanking.transactions.repository.TransactionRepository;

/** Pagamentos em paralelo (locks pessimistas no H2) e execução dos agendados. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalPaymentConcurrencyTest {

    private static final AtomicLong IDS = new AtomicLong(950_000);

    @Autowired PortalPaymentService service;
    @Autowired AccountRepository accounts;
    @Autowired PayableRepository payables;
    @Autowired PaymentRepository payments;
    @Autowired TransactionRepository transactions;
    @MockBean CurrentCustomerResolver resolver;

    Long customerId;
    LocalDate today;

    @BeforeEach
    void setUp() {
        customerId = IDS.incrementAndGet();
        today = LocalDate.now(PortalPaymentService.ZONE);
    }

    private Account account(String saldo) {
        return accounts.save(new Account(customerId, new BigDecimal(saldo)));
    }

    private Payable payable() {
        Payable p = new Payable();
        p.setCustomerId(customerId);
        p.setFornecedor("Fornecedor X");
        p.setCategoria(PayableCategory.FORNECEDOR);
        p.setVencimento(today.plusDays(3));
        p.setValor(new BigDecimal("150.00"));
        p.setStatus(PayableStatus.ABERTO);
        return payables.save(p);
    }

    private static PaymentRequest req(String valor, Long payableId, LocalDate agendadoPara) {
        return new PaymentRequest(PaymentType.PIX, new BigDecimal(valor), "Fornecedor X", null, payableId,
                agendadoPara, null);
    }

    /** Roda {@code n} pagamentos ao mesmo tempo e devolve o resultado (ou a exceção) de cada um. */
    private List<Object> parallel(int n, PaymentRequest req) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Object> task = () -> {
                start.await();
                return service.pay(new CurrentCustomer("user", customerId, "APROVADO"), req);
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
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError("pagamento travou", e);
            }
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    void mesmaContaAPagarEmParaleloEhPagaUmaVezSo() throws Exception {
        Account acc = account("1000.00");
        Payable payable = payable();

        List<Object> results = parallel(4, req("150.00", payable.getId(), null));

        assertThat(results).filteredOn(PaymentCreated.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> !(r instanceof PaymentCreated))
                .hasSize(3)
                .allSatisfy(r -> assertThat(r).isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CONTA_JA_PAGA")));
        assertThat(accounts.findById(acc.getId()).orElseThrow().getSaldo()).isEqualByComparingTo("850.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(acc.getId())).hasSize(1);
    }

    @Test
    void mesmoSaldoEmParaleloNaoFicaNegativo() throws Exception {
        Account acc = account("150.00");

        List<Object> results = parallel(3, req("100.00", null, null));

        assertThat(results).filteredOn(PaymentCreated.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> !(r instanceof PaymentCreated))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r).isInstanceOfSatisfying(SaldoInsuficienteException.class,
                        e -> assertThat(e.getFaltam()).isEqualByComparingTo("50.00")));
        assertThat(accounts.findById(acc.getId()).orElseThrow().getSaldo()).isEqualByComparingTo("50.00");
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(acc.getId())).hasSize(1);
    }

    @Test
    void agendadoEhExecutadoNaData() {
        Account acc = account("10.00");
        Payable payable = payable();
        LocalDate data = today.plusDays(5);
        Long id = service.pay(new CurrentCustomer("user", customerId, "APROVADO"), req("150.00", payable.getId(), data)).id();

        assertThat(service.dueScheduledIds(today)).doesNotContain(id);
        assertThat(service.executeScheduled(id, today)).isFalse();

        // Sem saldo na data: continua agendado.
        assertThat(service.dueScheduledIds(data)).contains(id);
        assertThat(service.executeScheduled(id, data)).isFalse();
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AGENDADO);
        assertThat(payables.findById(payable.getId()).orElseThrow().getStatus()).isEqualTo(PayableStatus.AGENDADO);

        Account funded = accounts.findById(acc.getId()).orElseThrow();
        funded.setSaldo(new BigDecimal("1000.00"));
        accounts.save(funded);

        assertThat(service.executeScheduled(id, data)).isTrue();
        Payment done = payments.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PaymentStatus.EFETIVADO);
        assertThat(done.getTransactionId()).isNotNull();
        Payable paga = payables.findById(payable.getId()).orElseThrow();
        assertThat(paga.getStatus()).isEqualTo(PayableStatus.PAGO);
        assertThat(paga.getPagoEm()).isNotNull();
        assertThat(accounts.findById(acc.getId()).orElseThrow().getSaldo()).isEqualByComparingTo("850.00");

        // Já efetivado: não debita de novo.
        assertThat(service.executeScheduled(id, data)).isFalse();
        assertThat(service.dueScheduledIds(data)).doesNotContain(id);
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(acc.getId())).hasSize(1);
    }

    @Test
    void agendadoSemContaPagarOuSemContaCorrente() {
        Payment semConta = new Payment();
        semConta.setCustomerId(customerId);
        semConta.setTipo(PaymentType.TED);
        semConta.setValor(BigDecimal.TEN);
        semConta.setDestinatario("Banco Y");
        semConta.setStatus(PaymentStatus.AGENDADO);
        semConta.setAgendadoPara(today);
        semConta.setAutenticacao(PortalPaymentService.newAuthentication());
        Long id = payments.save(semConta).getId();

        assertThat(service.executeScheduled(id, today)).isFalse();
        assertThat(service.executeScheduled(987_654_321L, today)).isFalse();

        Account acc = account("100.00");
        assertThat(service.executeScheduled(id, today)).isTrue();
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EFETIVADO);
        assertThat(accounts.findById(acc.getId()).orElseThrow().getSaldo()).isEqualByComparingTo("90.00");
    }
}
