package com.erpbanking.transactions.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.domain.Payable;
import com.erpbanking.transactions.domain.PayableStatus;
import com.erpbanking.transactions.domain.Payment;
import com.erpbanking.transactions.domain.PaymentStatus;
import com.erpbanking.transactions.domain.PaymentType;
import com.erpbanking.transactions.portal.CurrentCustomer;
import com.erpbanking.transactions.portal.Membro;
import com.erpbanking.transactions.portal.Permissoes;
import com.erpbanking.transactions.portal.PortalExceptions.BusinessRuleException;
import com.erpbanking.transactions.portal.PortalExceptions.NotFoundException;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentCreated;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentRequest;
import com.erpbanking.transactions.portal.payment.PaymentDtos.PaymentView;
import com.erpbanking.transactions.portal.payment.PortalPaymentService;

/**
 * Corridas no fluxo de aprovação (locks pessimistas no H2). Aprovação dupla e simulação reutilizada já têm teste
 * em PaymentApprovalMvcTest e MeCreditControllerTest; aqui: aprovar × rejeitar, dois pagamentos pendentes da mesma
 * conta a pagar e aprovação concorrente vinda de outra empresa.
 */
class ConcurrencyIsolationTest extends SecurityTestSupport {

    @Autowired PortalPaymentService service;

    private CurrentCustomer aprovador(Long empresa, int i) {
        return new CurrentCustomer("auth0|aprovador-" + empresa + "-" + i, empresa, "APROVADO",
                new Membro(10L + i, "FINANCEIRO", Permissoes.TODAS, null, "ATIVO", "Aprovador " + i));
    }

    private static List<Object> emParalelo(List<Callable<Object>> tarefas) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> t : tarefas) {
            futures.add(pool.submit(() -> {
                start.await();
                return t.call();
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            try {
                results.add(f.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                results.add(e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError("travou", e);
            }
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    void aprovarERejeitarAoMesmoTempoSoUmVence() throws Exception {
        for (Long empresa : List.of(empresaA, empresaB)) {
            Account conta = conta(empresa, "1000.00");
            Payable contaAPagar = contaAPagar(empresa, "Fornecedor", "600.00");
            Payment p = pagamentoPendente(empresa, "Fornecedor", "600.00", contaAPagar.getId(), "auth0|autor");
            Long id = p.getId();
            List<Object> r = emParalelo(List.of(
                    () -> service.approve(aprovador(empresa, 1), id),
                    () -> service.reject(aprovador(empresa, 2), id, "não"),
                    () -> service.approve(aprovador(empresa, 3), id),
                    () -> service.reject(aprovador(empresa, 4), id, null)));

            long vencedores = r.stream().filter(x -> x instanceof PaymentCreated || x instanceof PaymentView).count();
            assertThat(vencedores).isEqualTo(1);
            assertThat(r).filteredOn(BusinessRuleException.class::isInstance).hasSize(3)
                    .allSatisfy(e -> assertThat(((BusinessRuleException) e).getCode())
                            .isEqualTo("PAGAMENTO_NAO_PENDENTE"));

            Payment depois = payments.findById(id).orElseThrow();
            int debitos = transactions.findByAccountIdOrderByTimestampDescIdDesc(conta.getId()).size();
            if (depois.getStatus() == PaymentStatus.EFETIVADO) {
                assertThat(debitos).isEqualTo(1);
                assertThat(saldo(conta)).isEqualByComparingTo("400.00");
                assertThat(payables.findById(contaAPagar.getId()).orElseThrow().getStatus())
                        .isEqualTo(PayableStatus.PAGO);
            } else {
                assertThat(depois.getStatus()).isEqualTo(PaymentStatus.REJEITADO);
                assertThat(debitos).isZero();
                assertThat(saldo(conta)).isEqualByComparingTo("1000.00");
                assertThat(payables.findById(contaAPagar.getId()).orElseThrow().getStatus())
                        .isEqualTo(PayableStatus.ABERTO);
            }
        }
    }

    @Test
    void doisPagamentosAcimaDoLimiteDaMesmaContaAPagarSoUmFicaPendente() throws Exception {
        conta(empresaA, "1000.00");
        Payable contaAPagar = contaAPagar(empresaA, "Fornecedor", "600.00");
        PaymentRequest req = new PaymentRequest(PaymentType.BOLETO, new BigDecimal("600.00"), "Fornecedor", null,
                contaAPagar.getId(), null, null);
        List<Callable<Object>> tarefas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            CurrentCustomer op = new CurrentCustomer("auth0|op-" + i, empresaA, "APROVADO",
                    new Membro(20L + i, "OPERADOR", Permissoes.TODAS, new BigDecimal("100.00"), "ATIVO", "Op " + i));
            tarefas.add(() -> service.pay(op, req));
        }
        List<Object> r = emParalelo(tarefas);
        assertThat(r).filteredOn(PaymentCreated.class::isInstance).hasSize(1)
                .allSatisfy(x -> assertThat(((PaymentCreated) x).status()).isEqualTo(PaymentStatus.PENDENTE_APROVACAO));
        assertThat(r).filteredOn(BusinessRuleException.class::isInstance).hasSize(3)
                .allSatisfy(e -> assertThat(((BusinessRuleException) e).getCode()).isEqualTo("PAGAMENTO_PENDENTE"));
        assertThat(payments.findByCustomerIdAndStatusOrderByIdDesc(empresaA, PaymentStatus.PENDENTE_APROVACAO))
                .hasSize(1);
    }

    /** Aprovação legítima de A correndo com tentativas de B: B sempre 404 e não atrapalha o débito único. */
    @Test
    void aprovacaoDeOutraEmpresaEmParaleloDa404() throws Exception {
        Account conta = conta(empresaA, "1000.00");
        Long id = pagamentoPendente(empresaA, "Fornecedor", "600.00", null, "auth0|autor").getId();
        List<Object> r = emParalelo(List.of(
                () -> service.approve(aprovador(empresaB, 1), id),
                () -> service.reject(aprovador(empresaB, 2), id, null),
                () -> service.approve(aprovador(empresaA, 3), id),
                () -> service.approve(aprovador(empresaB, 4), id)));
        assertThat(r).filteredOn(PaymentCreated.class::isInstance).hasSize(1);
        assertThat(r).filteredOn(NotFoundException.class::isInstance).hasSize(3);
        assertThat(transactions.findByAccountIdOrderByTimestampDescIdDesc(conta.getId())).hasSize(1);
        assertThat(saldo(conta)).isEqualByComparingTo("400.00");
    }
}
