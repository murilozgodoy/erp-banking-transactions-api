package com.erpbanking.transactions.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.service.AccountService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MessagingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(MessagingConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(BusinessMetrics.class);

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
                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    for (String key : new String[]{"credit.decided", "transaction.created"}) {
                        for (String result : new String[]{"ok", "error"}) {
                            assertThat(registry.get(BusinessMetrics.EVENTS_PUBLISHED)
                                    .tags("routing_key", key, "result", result).counter().count()).isZero();
                        }
                    }
                });
    }

    @Test
    void autoOpenAccountDeclaresQueueBindingAndListener() {
        runner.withUserConfiguration(CustomerEventsConfig.class)
                .withBean(AccountService.class, () -> mock(AccountService.class))
                .withPropertyValues("spring.rabbitmq.addresses=amqp://erp:erp@rabbitmq:5672/",
                        "spring.rabbitmq.listener.simple.auto-startup=false", "messaging.auto-open-account=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CustomerCreatedListener.class);
                    assertThat(ctx.getBean(Queue.class).getName()).isEqualTo("transactions.customer-created");
                    Binding binding = ctx.getBean(Binding.class);
                    assertThat(binding.getExchange()).isEqualTo("erp.events");
                    assertThat(binding.getRoutingKey()).isEqualTo("customer.created");
                });
    }

    @Test
    void autoOpenAccountOffByDefault() {
        runner.withUserConfiguration(CustomerEventsConfig.class)
                .withBean(AccountService.class, () -> mock(AccountService.class))
                .withPropertyValues("spring.rabbitmq.addresses=amqp://erp:erp@rabbitmq:5672/")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CustomerCreatedListener.class));
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
