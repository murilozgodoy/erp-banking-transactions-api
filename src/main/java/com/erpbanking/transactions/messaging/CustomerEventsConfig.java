package com.erpbanking.transactions.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.service.AccountService;

/** Abertura automática de conta ({@code AUTO_OPEN_ACCOUNT=true} e mensageria ligada). */
@Configuration
@Conditional(MessagingEnabledCondition.class)
@ConditionalOnProperty(name = "messaging.auto-open-account", havingValue = "true")
public class CustomerEventsConfig {

    @Bean
    Queue customerCreatedQueue() {
        return QueueBuilder.durable(CustomerCreatedListener.QUEUE).build();
    }

    @Bean
    Binding customerCreatedBinding(Queue customerCreatedQueue, TopicExchange erpEventsExchange) {
        return BindingBuilder.bind(customerCreatedQueue).to(erpEventsExchange).with(CustomerCreatedListener.ROUTING_KEY);
    }

    @Bean
    CustomerCreatedListener customerCreatedListener(AccountService accountService, BusinessMetrics metrics) {
        return new CustomerCreatedListener(accountService, metrics);
    }
}
