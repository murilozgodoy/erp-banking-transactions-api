package com.erpbanking.transactions.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.portal.seed.PortalSeeder;
import com.erpbanking.transactions.portal.seed.SeederMetrics;

/** Seeder do portal do cliente: ligado sempre que a mensageria estiver ligada (independe de AUTO_OPEN_ACCOUNT). */
@Configuration
@Conditional(MessagingEnabledCondition.class)
public class OnboardingEventsConfig {

    @Bean
    Queue onboardingSubmittedQueue() {
        return QueueBuilder.durable(OnboardingSubmittedListener.QUEUE).build();
    }

    @Bean
    Binding onboardingSubmittedBinding(@Qualifier("onboardingSubmittedQueue") Queue queue,
                                       TopicExchange erpEventsExchange) {
        return BindingBuilder.bind(queue).to(erpEventsExchange).with(OnboardingSubmittedListener.ROUTING_KEY);
    }

    @Bean
    OnboardingSubmittedListener onboardingSubmittedListener(PortalSeeder seeder, BusinessMetrics metrics,
                                                            SeederMetrics seederMetrics) {
        return new OnboardingSubmittedListener(seeder, metrics, seederMetrics);
    }
}
