package com.erpbanking.transactions.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.portal.seed.PortalSeeder;
import com.erpbanking.transactions.portal.seed.SeederMetrics;
import com.erpbanking.transactions.service.AccountService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OnboardingEventsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(MessagingConfig.class, OnboardingEventsConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(BusinessMetrics.class)
            .withBean(SeederMetrics.class)
            .withBean(PortalSeeder.class, () -> mock(PortalSeeder.class));

    @Test
    void declaresDurableQueueWithoutArgumentsBoundToErpEvents() {
        runner.withPropertyValues("spring.rabbitmq.addresses=amqp://erp:erp@rabbitmq:5672/",
                        "spring.rabbitmq.listener.simple.auto-startup=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(OnboardingSubmittedListener.class);
                    Queue queue = ctx.getBean("onboardingSubmittedQueue", Queue.class);
                    assertThat(queue.getName()).isEqualTo("transactions.onboarding-submitted");
                    assertThat(queue.isDurable()).isTrue();
                    assertThat(queue.getArguments()).isEmpty();
                    Binding binding = ctx.getBean("onboardingSubmittedBinding", Binding.class);
                    assertThat(binding.getExchange()).isEqualTo("erp.events");
                    assertThat(binding.getDestination()).isEqualTo("transactions.onboarding-submitted");
                    assertThat(binding.getRoutingKey()).isEqualTo("customer.onboarding_submitted");
                    assertThat(ctx.getBean(MeterRegistry.class).get("seeder.runs").tags("result", "skipped")
                            .counter().count()).isZero();
                });
    }

    @Test
    void notGatedByAutoOpenAccountAndCoexistsWithIt() {
        runner.withUserConfiguration(CustomerEventsConfig.class)
                .withBean(AccountService.class, () -> mock(AccountService.class))
                .withPropertyValues("spring.rabbitmq.addresses=amqp://erp:erp@rabbitmq:5672/",
                        "spring.rabbitmq.listener.simple.auto-startup=false", "messaging.auto-open-account=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(OnboardingSubmittedListener.class)
                            .hasSingleBean(CustomerCreatedListener.class);
                    assertThat(ctx.getBeansOfType(Queue.class)).hasSize(2);
                    assertThat(ctx.getBean("customerCreatedBinding", Binding.class).getDestination())
                            .isEqualTo("transactions.customer-created");
                });
    }

    @Test
    void offWhenMessagingDisabled() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(OnboardingSubmittedListener.class)
                .doesNotHaveBean(Queue.class));
        runner.withPropertyValues("spring.rabbitmq.addresses=amqp://x@y:5672/", "messaging.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OnboardingSubmittedListener.class));
    }
}
