package com.erpbanking.transactions.config;

import org.springframework.web.client.RestClientResponseException;

/**
 * Descrição de exceções segura para log e para mensagens de erro: nunca inclui o corpo da resposta de outro
 * serviço (que pode trazer CPF/CNPJ, e-mail ou dados de outra empresa) nem cabeçalhos (Authorization).
 */
public final class LogSafe {

    private LogSafe() {}

    /** Ex.: {@code HttpServerErrorException 503} ou {@code ResourceAccessException}. */
    public static String describe(Throwable e) {
        if (e == null) {
            return "null";
        }
        if (e instanceof RestClientResponseException r) {
            return e.getClass().getSimpleName() + " " + r.getStatusCode().value();
        }
        return e.getClass().getSimpleName();
    }
}
