package com.erpbanking.transactions.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.ConnectException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.erpbanking.transactions.metrics.BusinessMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RabbitEventPublisherTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RabbitEventPublisher publisher =
            new RabbitEventPublisher(template, "erp.events", new BusinessMetrics(registry));

    private double count(String result) {
        return registry.get(BusinessMetrics.EVENTS_PUBLISHED)
                .tags("routing_key", "credit.decided", "result", result).counter().count();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesEnvelopeOnTopicExchangeAndCountsOk() {
        publisher.publish("credit.decided", Map.of("customer_id", 7L));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(eq("erp.events"), eq("credit.decided"), body.capture(), mpp.capture());

        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertThat(sent).containsEntry("event_type", "credit.decided").containsEntry("customer_id", 7L)
                .containsKeys("event_id", "occurred_at");
        assertThat(UUID.fromString((String) sent.get("event_id"))).isNotNull();
        assertThat((String) sent.get("occurred_at")).endsWith("Z");

        Message message = mpp.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(sent.get("event_id"));
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getType()).isEqualTo("credit.decided");
        assertThat(count("ok")).isEqualTo(1.0);
    }

    @Test
    void brokerFailureNeverPropagatesAndCountsError() {
        doThrow(new AmqpConnectException(new ConnectException("refused")))
                .when(template).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));

        publisher.publish("credit.decided", Map.of());

        assertThat(count("error")).isEqualTo(1.0);
    }

    @Test
    void noopPublisherDoesNothing() {
        new NoopEventPublisher().publish("credit.decided", Map.of("a", 1));
    }
}
