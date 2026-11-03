package com.erpbanking.transactions.portal;

/**
 * Cliente do portal (empresa do token), resolvido pelo Onboarding {@code GET /me}, e o membro da empresa que está
 * usando o token ({@link Membro}; DONO implícito quando o Onboarding ainda não devolve {@code membro}).
 */
public record CurrentCustomer(String sub, Long customerId, String onboardingStatus, Membro membro) {

    public static final String APROVADO = "APROVADO";

    public CurrentCustomer {
        if (membro == null) {
            membro = Membro.donoImplicito(null);
        }
    }

    /** DONO implícito (todas as permissões, sem limite). */
    public CurrentCustomer(String sub, Long customerId, String onboardingStatus) {
        this(sub, customerId, onboardingStatus, null);
    }

    public boolean aprovado() {
        return customerId != null && APROVADO.equals(onboardingStatus);
    }

    public boolean tem(String permissao) {
        return membro.tem(permissao);
    }

    /** Nome do membro para a auditoria ({@code created_by_nome}); pode ser {@code null}. */
    public String nome() {
        return membro.nome();
    }
}
