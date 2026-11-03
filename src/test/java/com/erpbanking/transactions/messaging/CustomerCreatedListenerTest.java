package com.erpbanking.transactions.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.erpbanking.transactions.domain.Account;
import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.service.AccountService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CustomerCreatedListenerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AccountService accountService = mock(AccountService.class);
    private final CustomerCreatedListener listener =
            new CustomerCreatedListener(accountService, new BusinessMetrics(registry));

    private double consumed(String result) {
        return registry.get(BusinessMetrics.EVENTS_CONSUMED)
                .tags("routing_key", "customer.created", "result", result).counter().count();
    }

    @Test
    void opensAccountForNewCustomer() {
        Account account = new Account(42L, BigDecimal.ZERO);
        account.setId(1L);
        when(accountService.openForNewCustomer(42L)).thenReturn(Optional.of(account));

        listener.onCustomerCreated(Map.of("event_id", "e1", "customer_id", 42, "nome", "Ana"));

        verify(accountService).openForNewCustomer(42L);
        assertThat(consumed("ok")).isEqualTo(1.0);
    }

    @Test
    void seriesStartAtZero() {
        assertThat(consumed("ok")).isZero();
        assertThat(consumed("duplicate")).isZero();
        assertThat(consumed("invalid")).isZero();
        assertThat(consumed("error")).isZero();
    }

    @Test
    void failureToOpenAccountCountsErrorAndRethrows() {
        when(accountService.openForNewCustomer(42L)).thenThrow(new IllegalStateException("db fora"));

        assertThatThrownBy(() -> listener.onCustomerCreated(Map.of("customer_id", 42)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(consumed("error")).isEqualTo(1.0);
        assertThat(consumed("ok")).isZero();
    }

    @Test
    void redeliveryIsIgnored() {
        when(accountService.openForNewCustomer(42L)).thenReturn(Optional.empty());

        listener.onCustomerCreated(Map.of("customer_id", "42"));

        assertThat(consumed("duplicate")).isEqualTo(1.0);
    }

    @Test
    void invalidPayloadIsRejectedWithoutRequeue() {
        Map<String, Object> noId = new HashMap<>();
        noId.put("customer_id", null);
        for (Map<String, Object> event : new Map[]{noId, Map.of("customer_id", "abc"), Map.of("customer_id", " "),
                Map.of("nome", "x")}) {
            assertThatThrownBy(() -> listener.onCustomerCreated(event))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        assertThat(CustomerCreatedListener.customerId(null)).isNull();
        verify(accountService, never()).openForNewCustomer(any());
        assertThat(consumed("invalid")).isEqualTo(4.0);
    }
}
