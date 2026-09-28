package com.erpbanking.transactions.exception;

public final class ApiExceptions {

    private ApiExceptions() {}

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String msg) { super(msg); }
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String msg) { super(msg); }
    }

    public static class AccountBlockedException extends RuntimeException {
        public AccountBlockedException(String msg) { super(msg); }
    }

    public static class CreditRequestNotFoundException extends RuntimeException {
        public CreditRequestNotFoundException(String msg) { super(msg); }
    }

    public static class ScoreUnavailableException extends RuntimeException {
        public ScoreUnavailableException(String msg) { super(msg); }
    }
}
