package com.erpbanking.transactions.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.erpbanking.transactions.domain.PaymentType;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class PortalMetricsTest {

    @Autowired MockMvc mvc;

    @Test
    void prometheusExpoeSeriesDoPortalComZero() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "payments_total{application=\"transactions-api\",status=\"RECUSADO\",tipo=\"PIX\"")))
                .andExpect(content().string(containsString(
                        "payments_total{application=\"transactions-api\",status=\"PENDENTE_APROVACAO\",tipo=\"TED\"")))
                .andExpect(content().string(containsString(
                        "payments_total{application=\"transactions-api\",status=\"REJEITADO\",tipo=\"BOLETO\"")))
                .andExpect(content().string(containsString(
                        "payments_amount_brl_total{application=\"transactions-api\",tipo=\"TED\"")))
                .andExpect(content().string(containsString(
                        "credit_simulations_total{application=\"transactions-api\",aprovacao=\"PRE_APROVADO\","
                                + "produto=\"ANTECIPACAO_RECEBIVEIS\"")))
                .andExpect(content().string(containsString(
                        "credit_portal_requests_total{application=\"transactions-api\",produto=\"CONSOLIDACAO_DIVIDAS\"")));
    }

    @Test
    void valorSomaSoNosEfetivados() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PortalMetrics metrics = new PortalMetrics(registry);
        metrics.payment(PaymentType.PIX, PortalMetrics.EFETIVADO, new BigDecimal("10.50"));
        metrics.payment(PaymentType.PIX, PortalMetrics.AGENDADO, new BigDecimal("99"));
        metrics.payment(PaymentType.PIX, PortalMetrics.EFETIVADO, null);
        metrics.simulation("ALAVANCAGEM", "NEGADO");
        metrics.portalRequest("ALAVANCAGEM");

        assertThat(registry.get(PortalMetrics.PAYMENTS_AMOUNT).tag("tipo", "PIX").counter().count()).isEqualTo(10.5);
        assertThat(registry.get(PortalMetrics.PAYMENTS).tags("tipo", "PIX", "status", "EFETIVADO").counter().count())
                .isEqualTo(2);
        assertThat(registry.get(PortalMetrics.CREDIT_SIMULATIONS).tags("produto", "ALAVANCAGEM", "aprovacao", "NEGADO")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(PortalMetrics.CREDIT_PORTAL_REQUESTS).tag("produto", "ALAVANCAGEM").counter().count())
                .isEqualTo(1);
    }

    @Test
    void validacaoDoPortalVira400() {
        var resp = new PortalExceptionHandler().validation(new PortalExceptions.ValidationException("x"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "validation_failed").containsEntry("message", "x");
    }
}
