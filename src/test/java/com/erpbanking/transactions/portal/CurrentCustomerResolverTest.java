package com.erpbanking.transactions.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentCustomerResolverTest {

    private final OnboardingClient onboarding = mock(OnboardingClient.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T12:00:00Z"));
    private CurrentCustomerResolver resolver;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        resolver = new CurrentCustomerResolver(onboarding, clock);
        login("auth0|abc");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String sub) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(sub, null));
    }

    @Test
    void ttlDoCacheEh60Segundos() {
        assertThat(CurrentCustomerResolver.TTL).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void semMembroEhDonoComTodasAsPermissoesESemLimite() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO", null, " Ana Dona "));

        CurrentCustomer c = resolver.requirePermission(Permissoes.CONTRATAR_CREDITO);

        assertThat(c.membro().papel()).isEqualTo(Membro.DONO);
        assertThat(c.membro().permissoes()).containsExactlyInAnyOrderElementsOf(Permissoes.TODAS);
        assertThat(c.membro().limitePagamento()).isNull();
        assertThat(c.membro().excedeLimite(new BigDecimal("1000000"))).isFalse();
        assertThat(c.nome()).isEqualTo("Ana Dona");
        assertThat(c.sub()).isEqualTo("auth0|abc");
    }

    @Test
    void membroComPermissoesELimite() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO",
                new OnboardingClient.MembroResponse(3L, "OPERADOR", List.of("ver_financeiro", "pagar"),
                        new BigDecimal("500.00"), "ATIVO", "Otávio"), "Outro nome"));

        CurrentCustomer c = resolver.requirePermission(Permissoes.PAGAR);

        assertThat(c.membro().id()).isEqualTo(3L);
        assertThat(c.nome()).isEqualTo("Otávio");
        assertThat(c.membro().excedeLimite(new BigDecimal("500.00"))).isFalse();
        assertThat(c.membro().excedeLimite(new BigDecimal("500.01"))).isTrue();
        assertThatThrownBy(() -> resolver.requirePermission(Permissoes.APROVAR_PAGAMENTOS))
                .isInstanceOf(PermissionDeniedException.class)
                .extracting("permissao").isEqualTo("aprovar_pagamentos");
        verify(onboarding, times(1)).me();
    }

    @Test
    void membroSemNomeUsaONomeDoTopoESemPermissoesNaoTemNenhuma() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO",
                new OnboardingClient.MembroResponse(3L, "LEITURA", null, null, "ATIVO", " "), "Lia"));

        CurrentCustomer c = resolver.require();

        assertThat(c.nome()).isEqualTo("Lia");
        assertThat(c.membro().permissoes()).isEmpty();
        assertThat(c.tem(Permissoes.VER_FINANCEIRO)).isFalse();
    }

    @Test
    void membroSuspensoDa403ENaoFicaEmCache() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO",
                new OnboardingClient.MembroResponse(3L, "OPERADOR", List.of("pagar"), null, "SUSPENSO", null), null));
        assertThatThrownBy(resolver::require).isInstanceOf(MemberSuspendedException.class);

        when(onboarding.me()).thenThrow(new MemberSuspendedException());
        assertThatThrownBy(resolver::require).isInstanceOf(MemberSuspendedException.class);
        verify(onboarding, times(2)).me();
    }

    @Test
    void permissaoRemovidaValeDepoisDoTtl() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO",
                new OnboardingClient.MembroResponse(3L, "FINANCEIRO", List.of("pagar"), null, "ATIVO", null), null));
        resolver.requirePermission(Permissoes.PAGAR);

        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO",
                new OnboardingClient.MembroResponse(3L, "FINANCEIRO", List.of(), null, "ATIVO", null), null));
        resolver.requirePermission(Permissoes.PAGAR);

        now.set(now.get().plus(CurrentCustomerResolver.TTL));
        assertThatThrownBy(() -> resolver.requirePermission(Permissoes.PAGAR))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void corpoDoSemPermissao() {
        assertThat(new PermissionDeniedException("pagar").body())
                .containsExactly(Map.entry("error", "SEM_PERMISSAO"), Map.entry("permissao", "pagar"));
        assertThat(new PermissionDeniedException("aprovar_pagamentos", "AUTOR").body())
                .containsEntry("motivo", "AUTOR");
    }

    @Test
    void aprovadoDevolveOCustomerEFicaEmCachePorTtl() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO"));

        assertThat(resolver.requireCustomerId()).isEqualTo(7L);
        assertThat(resolver.require().sub()).isEqualTo("auth0|abc");
        verify(onboarding, times(1)).me();

        now.set(now.get().plus(CurrentCustomerResolver.TTL).plus(Duration.ofSeconds(1)));
        resolver.require();
        verify(onboarding, times(2)).me();
    }

    @Test
    void cacheEPorSub() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "APROVADO"));
        resolver.require();
        login("auth0|outro");
        resolver.require();
        verify(onboarding, times(2)).me();
    }

    @Test
    void emAnaliseDa409ENaoFicaEmCache() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(7L, "EM_ANALISE"));

        assertThatThrownBy(resolver::require)
                .isInstanceOf(OnboardingPendingException.class)
                .extracting("status").isEqualTo("EM_ANALISE");
        assertThatThrownBy(resolver::require).isInstanceOf(OnboardingPendingException.class);
        verify(onboarding, times(2)).me();
    }

    @Test
    void semCustomerViraNaoIniciado() {
        when(onboarding.me()).thenReturn(new OnboardingClient.MeResponse(null, null));

        assertThatThrownBy(resolver::require)
                .isInstanceOf(OnboardingPendingException.class)
                .extracting("status").isEqualTo("NAO_INICIADO");
    }

    @Test
    void semAutenticacaoFalha() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(resolver::resolve).isInstanceOf(IllegalStateException.class);
    }
}
