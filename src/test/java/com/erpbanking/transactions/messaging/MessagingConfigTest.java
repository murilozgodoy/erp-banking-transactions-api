package com.erpbanking.transactions.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.erpbanking.transactions.metrics.BusinessMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MessagingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(MessagingConfig.class)
            .withBean(BusinessMetrics.class, () -> new BusinessMetrics(new SimpleMeterRegistry()));

    @Test
    void disabledWithoutRabbitUrl() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(EventPublisher.class)).isInstanceOf(NoopEventPublisher.class);
            assertThat(ctx).doesNotHaveBean(TopicExchange.class);
        });
    }

    @Test
    void enabledWhenRabbitUrlIsSet() {
        runner.withPropertyValues("spring.rabbitmq.addresses=amqp://erp:erp@rabbitmq:5672/")
                .run(ctx -> {
                    assertThat(ctx.getBean(EventPublisher.class)).isInstanceOf(RabbitEventPublisher.class);
                    TopicExchange exchange = ctx.getBean(TopicExchange.class);
                    assertThat(exchange.getName()).isEqualTo("erp.events");
                    assertThat(exchange.isDurable()).isTrue();
                });
    }

    @Test
    void messagingEnabledFalseWins() {
        runner.withPropertyValues("spring.rabbitmq.addresses=amqp://erp:erp@rabbitmq:5672/", "messaging.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(EventPublisher.class)).isInstanceOf(NoopEventPublisher.class));
    }

    @Test
    void messagingEnabledTrueWithoutUrlUsesDefaultBroker() {
        runner.withPropertyValues("messaging.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(EventPublisher.class)).isInstanceOf(RabbitEventPublisher.class));
    }
}
