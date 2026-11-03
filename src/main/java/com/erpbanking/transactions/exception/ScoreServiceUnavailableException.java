package com.erpbanking.transactions.exception;

/**
 * Credit Score indisponível: tentativas esgotadas em falhas transitórias ou circuito aberto.
 * Mapeada para HTTP 503 pelo {@link GlobalExceptionHandler}.
 */
public class ScoreServiceUnavailableException extends RuntimeException {

    public static final String MESSAGE =
            "Serviço de score indisponível no momento. Tente novamente em instantes.";

    public ScoreServiceUnavailableException() {
        super(MESSAGE);
    }

    public ScoreServiceUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
