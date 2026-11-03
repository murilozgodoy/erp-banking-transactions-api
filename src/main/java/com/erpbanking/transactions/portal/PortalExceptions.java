package com.erpbanking.transactions.portal;

import java.math.BigDecimal;

/** Erros de negócio das rotas /me do portal, mapeados pelo {@link PortalExceptionHandler}. */
public final class PortalExceptions {

    private PortalExceptions() {}

    /** Base: erro com código estável (campo {@code error}) e mensagem legível. */
    public abstract static class PortalException extends RuntimeException {
        private final String code;

        protected PortalException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /** Recurso inexistente ou de outro cliente: 404. */
    public static class NotFoundException extends PortalException {
        public NotFoundException(String code, String message) { super(code, message); }
    }

    /** Regra de negócio violada: 422. */
    public static class BusinessRuleException extends PortalException {
        public BusinessRuleException(String code, String message) { super(code, message); }
    }

    /** Corpo válido no formato mas inconsistente (ex.: prazo acima do máximo): 400. */
    public static class ValidationException extends PortalException {
        public ValidationException(String message) { super("validation_failed", message); }
    }

    /** Saldo não cobre o pagamento: 422 com quanto falta. */
    public static class SaldoInsuficienteException extends PortalException {
        private final BigDecimal faltam;

        public SaldoInsuficienteException(BigDecimal faltam) {
            super("SALDO_INSUFICIENTE", "Saldo insuficiente: faltam " + faltam);
            this.faltam = faltam;
        }

        public BigDecimal getFaltam() {
            return faltam;
        }
    }
}
