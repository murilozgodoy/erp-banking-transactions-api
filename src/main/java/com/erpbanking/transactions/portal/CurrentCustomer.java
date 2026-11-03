package com.erpbanking.transactions.portal;

/** Cliente do portal (dono do token), resolvido pelo Onboarding {@code GET /me}. */
public record CurrentCustomer(String sub, Long customerId, String onboardingStatus) {

    public static final String APROVADO = "APROVADO";

    public boolean aprovado() {
        return customerId != null && APROVADO.equals(onboardingStatus);
    }
}
