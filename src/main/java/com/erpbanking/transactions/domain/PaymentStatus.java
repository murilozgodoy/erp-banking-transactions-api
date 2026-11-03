package com.erpbanking.transactions.domain;

/** Situação de um pagamento feito pelo portal. */
public enum PaymentStatus {
    EFETIVADO,
    AGENDADO,
    /** Valor acima do {@code limite_pagamento} do membro: sem débito até alguém com {@code aprovar_pagamentos} aprovar. */
    PENDENTE_APROVACAO,
    /** Pendente rejeitado por quem aprova; nunca debita. */
    REJEITADO
}
