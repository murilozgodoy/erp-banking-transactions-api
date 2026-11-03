package com.erpbanking.transactions.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.portal.seed.PortalSeeder;
import com.erpbanking.transactions.portal.seed.SeedProfile;
import com.erpbanking.transactions.portal.seed.SeederMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OnboardingSubmittedListenerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PortalSeeder seeder = mock(PortalSeeder.class);
    private final OnboardingSubmittedListener listener =
            new OnboardingSubmittedListener(seeder, new BusinessMetrics(registry), new SeederMetrics(registry));

    private double consumed(String result) {
        return registry.get(BusinessMetrics.EVENTS_CONSUMED)
                .tags("routing_key", "customer.onboarding_submitted", "result", result).counter().count();
    }

    private double runs(String result) {
        return registry.get("seeder.runs").tags("result", result).counter().count();
    }

    @Test
    void seriesStartAtZero() {
        for (String r : new String[]{"ok", "duplicate", "invalid", "error"}) {
            assertThat(consumed(r)).isZero();
        }
        for (String r : new String[]{"ok", "skipped", "error"}) {
            assertThat(runs(r)).isZero();
        }
    }

    @Test
    void seedsFromPayload() {
        when(seeder.seed(any())).thenReturn(PortalSeeder.Outcome.SEEDED);

        listener.onOnboardingSubmitted(Map.of("event_id", "e1", "event_type", "customer.onboarding_submitted",
                "customer_id", 42, "segmento", "saude", "respostas", Map.of("faturamento_mensal", "80000")));

        ArgumentCaptor<SeedProfile> captor = ArgumentCaptor.forClass(SeedProfile.class);
        verify(seeder).seed(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo(42);
        assertThat(captor.getValue().segmento()).isEqualTo("saude");
        assertThat(captor.getValue().faturamentoMensal()).isEqualByComparingTo("80000");
        assertThat(consumed("ok")).isEqualTo(1.0);
        assertThat(runs("ok")).isEqualTo(1.0);
    }

    @Test
    void alreadySeededCountsDuplicateAndSkipped() {
        when(seeder.seed(any())).thenReturn(PortalSeeder.Outcome.ALREADY_SEEDED);

        listener.onOnboardingSubmitted(Map.of("customer_id", "42"));

        assertThat(consumed("duplicate")).isEqualTo(1.0);
        assertThat(runs("skipped")).isEqualTo(1.0);
    }

    @Test
    void failureCountsErrorAndRethrows() {
        when(seeder.seed(any())).thenThrow(new IllegalStateException("db fora"));

        assertThatThrownBy(() -> listener.onOnboardingSubmitted(Map.of("customer_id", 42)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(consumed("error")).isEqualTo(1.0);
        assertThat(runs("error")).isEqualTo(1.0);
        assertThat(consumed("ok")).isZero();
    }

    @Test
    void invalidPayloadIsRejectedWithoutRequeue() {
        Map<String, Object> noId = new HashMap<>();
        noId.put("customer_id", null);
        for (Map<String, Object> event : new Map[]{noId, Map.of("customer_id", "abc"), Map.of("customer_id", 0),
                Map.of("customer_id", -5), Map.of("segmento", "varejo")}) {
            assertThatThrownBy(() -> listener.onOnboardingSubmitted(event))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        assertThatThrownBy(() -> listener.onOnboardingSubmitted(null))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(seeder, never()).seed(any());
        assertThat(consumed("invalid")).isEqualTo(6.0);
    }
}
