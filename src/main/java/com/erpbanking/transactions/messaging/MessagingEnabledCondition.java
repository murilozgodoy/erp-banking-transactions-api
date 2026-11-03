package com.erpbanking.transactions.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Mensageria ligada quando {@code MESSAGING_ENABLED=true}, ou quando ele está vazio e {@code RABBITMQ_URL}
 * está definida. {@code MESSAGING_ENABLED=false} sempre desliga.
 */
public class MessagingEnabledCondition extends SpringBootCondition {

    public static boolean isEnabled(Environment env) {
        String flag = env.getProperty("messaging.enabled", "");
        if (!flag.isBlank()) {
            return Boolean.parseBoolean(flag.trim());
        }
        return !env.getProperty("spring.rabbitmq.addresses", "").isBlank();
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isEnabled(context.getEnvironment())
                ? ConditionOutcome.match("mensageria ligada")
                : ConditionOutcome.noMatch("mensageria desligada (RABBITMQ_URL vazia ou MESSAGING_ENABLED=false)");
    }

    /** Inverso, para o publisher no-op. */
    public static class Disabled extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return isEnabled(context.getEnvironment())
                    ? ConditionOutcome.noMatch("mensageria ligada")
                    : ConditionOutcome.match("mensageria desligada");
        }
    }
}
