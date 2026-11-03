package com.erpbanking.transactions.portal;

/** Membro SUSPENSO na empresa (403 do Onboarding {@code GET /me}): as rotas /me respondem 403 MEMBRO_SUSPENSO. */
public class MemberSuspendedException extends RuntimeException {

    public static final String CODE = "MEMBRO_SUSPENSO";

    public MemberSuspendedException() {
        super("Membro suspenso na empresa");
    }
}
