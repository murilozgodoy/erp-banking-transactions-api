package com.erpbanking.transactions.messaging;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Usado quando a mensageria está desligada (RABBITMQ_URL vazia): dev e testes rodam sem broker. */
public class NoopEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopEventPublisher.class);

    @Override
    public void publish(String routingKey, Map<String, Object> payload) {
        log.debug("Mensageria desligada; evento {} descartado", routingKey);
    }
}
