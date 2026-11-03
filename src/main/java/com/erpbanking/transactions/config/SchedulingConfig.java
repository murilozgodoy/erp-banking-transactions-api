package com.erpbanking.transactions.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Liga o agendador (pagamentos agendados do portal); {@code portal.payments.scheduler-enabled=false} desliga. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "portal.payments.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
