package com.erpbanking.transactions.portal;

import java.util.Set;

/**
 * Permissões finas por empresa (contrato da seção 11 do PORTAL_CLIENTE). Vêm do Onboarding {@code GET /me} em
 * {@code membro.permissoes} e são aplicadas por {@link CurrentCustomerResolver#requirePermission(String)}.
 */
public final class Permissoes {

    private Permissoes() {}

    public static final String VER_FINANCEIRO = "ver_financeiro";
    public static final String PAGAR = "pagar";
    public static final String APROVAR_PAGAMENTOS = "aprovar_pagamentos";
    public static final String PEDIR_CREDITO = "pedir_credito";
    public static final String CONTRATAR_CREDITO = "contratar_credito";
    /** Exigida por {@code POST /me/inventory/{id}/movements} (movimentação de estoque). */
    public static final String GERENCIAR_ESTOQUE = "gerenciar_estoque";
    public static final String GERENCIAR_EQUIPE = "gerenciar_equipe";
    public static final String USAR_ASSISTENTE = "usar_assistente";

    public static final Set<String> TODAS = Set.of(VER_FINANCEIRO, PAGAR, APROVAR_PAGAMENTOS, PEDIR_CREDITO,
            CONTRATAR_CREDITO, GERENCIAR_ESTOQUE, GERENCIAR_EQUIPE, USAR_ASSISTENTE);
}
