package com.erpbanking.transactions.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;

/** RabbitMQ (exchange topic durável {@code erp.events}) ou no-op, conforme {@link MessagingEnabledCondition}. */
@Configuration
public class MessagingConfig {

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    @Conditional(MessagingEnabledCondition.class)
    TopicExchange erpEventsExchange(@Value("${messaging.exchange:erp.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    @Conditional(MessagingEnabledCondition.class)
    EventPublisher rabbitEventPublisher(RabbitTemplate rabbitTemplate, BusinessMetrics metrics,
                                        @Value("${messaging.exchange:erp.events}") String exchange) {
        return new RabbitEventPublisher(rabbitTemplate, exchange, metrics);
    }

    @Bean
    @Conditional(MessagingEnabledCondition.Disabled.class)
    EventPublisher noopEventPublisher() {
        return new NoopEventPublisher();
    }
}
