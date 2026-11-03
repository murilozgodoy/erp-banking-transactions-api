package com.erpbanking.transactions.portal;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Membro da empresa dono do token ({@code membro} do Onboarding {@code GET /me}).
 *
 * @param limitePagamento {@code null} = sem limite
 * @param nome            nome de exibição para auditoria ({@code created_by_nome}); opcional
 */
public record Membro(Long id, String papel, Set<String> permissoes, BigDecimal limitePagamento, String status,
                     String nome) {

    public static final String DONO = "DONO";
    public static final String ATIVO = "ATIVO";
    public static final String SUSPENSO = "SUSPENSO";

    public Membro {
        permissoes = permissoes == null ? Set.of() : Set.copyOf(permissoes);
    }

    /**
     * Sem {@code membro} no {@code GET /me} (Onboarding ainda sem a equipe): o dono do token é o DONO, com todas as
     * permissões e sem limite de pagamento.
     */
    public static Membro donoImplicito(String nome) {
        return new Membro(null, DONO, Permissoes.TODAS, null, ATIVO, nome);
    }

    public boolean tem(String permissao) {
        return permissoes.contains(permissao);
    }

    /** {@code true} se há limite e o valor passa dele. */
    public boolean excedeLimite(BigDecimal valor) {
        return limitePagamento != null && valor != null && valor.compareTo(limitePagamento) > 0;
    }
}
