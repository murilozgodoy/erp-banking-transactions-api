package com.erpbanking.transactions.portal;

/** Cliente sem customer ou com onboarding diferente de APROVADO: as rotas /me respondem 409. */
public class OnboardingPendingException extends RuntimeException {

    private final String status;

    public OnboardingPendingException(String status) {
        super("Onboarding pendente: " + status);
        this.status = status;
    }

    public String getStatus() {
        return status;
    }
}
