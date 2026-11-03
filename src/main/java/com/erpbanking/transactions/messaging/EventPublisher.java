package com.erpbanking.transactions.messaging;

import java.util.Map;

/** Publica eventos de integração na exchange {@code erp.events}. Nunca lança exceção. */
public interface EventPublisher {

    /**
     * @param routingKey ex.: {@code credit.decided}; vira também o {@code event_type}
     * @param payload campos específicos do evento (snake_case)
     */
    void publish(String routingKey, Map<String, Object> payload);
}
