package com.erpbanking.transactions.portal.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ScheduledPaymentsJobTest {

    private final PortalPaymentService payments = mock(PortalPaymentService.class);
    // 02:00 UTC = 23:00 do dia anterior em São Paulo.
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"), PortalPaymentService.ZONE);
    private final LocalDate today = LocalDate.of(2026, 11, 3);

    @Test
    void executaOsVencidosEContinuaAposFalha() {
        when(payments.dueScheduledIds(today)).thenReturn(List.of(1L, 2L, 3L));
        when(payments.executeScheduled(1L, today)).thenReturn(true);
        when(payments.executeScheduled(2L, today)).thenThrow(new IllegalStateException("conta bloqueada"));
        when(payments.executeScheduled(3L, today)).thenReturn(false);

        assertThat(new ScheduledPaymentsJob(payments, clock).run()).isEqualTo(1);

        verify(payments).executeScheduled(3L, today);
    }

    @Test
    void semVencidosNaoFazNada() {
        when(payments.dueScheduledIds(today)).thenReturn(List.of());
        assertThat(new ScheduledPaymentsJob(payments, clock).run()).isZero();
        assertThat(new ScheduledPaymentsJob(payments)).isNotNull();
    }
}
