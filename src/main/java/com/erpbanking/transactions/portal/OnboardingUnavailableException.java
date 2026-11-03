package com.erpbanking.transactions.portal;

/** Onboarding API fora do ar ou respondendo erro: as rotas /me respondem 503. */
public class OnboardingUnavailableException extends RuntimeException {

    public OnboardingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
