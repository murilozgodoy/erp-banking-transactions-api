package com.erpbanking.transactions.messaging;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.portal.seed.PortalSeeder;
import com.erpbanking.transactions.portal.seed.SeedProfile;
import com.erpbanking.transactions.portal.seed.SeederMetrics;

/**
 * Consome {@code customer.onboarding_submitted} (Onboarding) na fila {@code transactions.onboarding-submitted}:
 * abre a conta PJ, se preciso, e gera a massa do portal do cliente. Idempotente (cliente com vendas = duplicate).
 * Payload sem {@code customer_id} válido é descartado sem requeue; erro ao gravar segue para o container (retry).
 */
public class OnboardingSubmittedListener {

    public static final String QUEUE = "transactions.onboarding-submitted";
    public static final String ROUTING_KEY = "customer.onboarding_submitted";

    private static final Logger log = LoggerFactory.getLogger(OnboardingSubmittedListener.class);

    private final PortalSeeder seeder;
    private final BusinessMetrics metrics;
    private final SeederMetrics seederMetrics;

    public OnboardingSubmittedListener(PortalSeeder seeder, BusinessMetrics metrics, SeederMetrics seederMetrics) {
        this.seeder = seeder;
        this.metrics = metrics;
        this.seederMetrics = seederMetrics;
        metrics.initConsumed(ROUTING_KEY, "ok", "duplicate", "invalid", "error");
    }

    @RabbitListener(queues = QUEUE)
    public void onOnboardingSubmitted(Map<String, Object> event) {
        Long customerId = CustomerCreatedListener.customerId(event);
        if (customerId == null || customerId <= 0) {
            log.warn("customer.onboarding_submitted sem customer_id valido, descartado: event_id={}",
                    event == null ? null : event.get("event_id"));
            metrics.eventConsumed(ROUTING_KEY, "invalid");
            throw new AmqpRejectAndDontRequeueException("customer_id ausente ou invalido");
        }
        PortalSeeder.Outcome outcome;
        try {
            outcome = seeder.seed(SeedProfile.from(customerId, event));
        } catch (RuntimeException e) {
            log.error("Falha ao popular o portal do cliente {}", customerId, e);
            metrics.eventConsumed(ROUTING_KEY, "error");
            seederMetrics.run(SeederMetrics.ERROR);
            throw e;
        }
        if (outcome == PortalSeeder.Outcome.SEEDED) {
            metrics.eventConsumed(ROUTING_KEY, "ok");
            seederMetrics.run(SeederMetrics.OK);
        } else {
            log.info("Cliente {} ja tem dados no portal; customer.onboarding_submitted ignorado", customerId);
            metrics.eventConsumed(ROUTING_KEY, "duplicate");
            seederMetrics.run(SeederMetrics.SKIPPED);
        }
    }
}
