package com.erpbanking.transactions.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
    void aprovadoDevolveOCustomerEFicaEmCachePor5Minutos() {
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
