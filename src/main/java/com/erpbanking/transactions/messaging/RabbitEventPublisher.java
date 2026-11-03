package com.erpbanking.transactions.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.erpbanking.transactions.metrics.BusinessMetrics;

/**
 * Publica em JSON, persistente, com {@code event_id}, {@code event_type} e {@code occurred_at}.
 * Falha de broker só gera log e {@code events_published_total{result="error"}}: a requisição HTTP segue.
 */
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final BusinessMetrics metrics;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate, String exchange, BusinessMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.metrics = metrics;
    }

    @Override
    public void publish(String routingKey, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", eventId);
        body.put("event_type", routingKey);
        body.put("occurred_at", Instant.now().toString());
        body.putAll(payload);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, body, message -> decorate(message, eventId, routingKey));
            metrics.eventPublished(routingKey, "ok");
        } catch (RuntimeException e) {
            log.warn("Falha ao publicar {} ({}): {}", routingKey, eventId, e.getMessage());
            metrics.eventPublished(routingKey, "error");
        }
    }

    static Message decorate(Message message, String eventId, String routingKey) {
        MessageProperties props = message.getMessageProperties();
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(eventId);
        props.setType(routingKey);
        return message;
    }
}
