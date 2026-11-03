package com.erpbanking.transactions.portal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Descobre o {@code customer_id} do dono do token pelo Onboarding {@code GET /me} (token relay).
 * Cache em memória de 5 min por {@code sub}, só para clientes APROVADO: quem ainda está em análise
 * consulta de novo a cada chamada, para o portal liberar assim que a aprovação sair.
 */
@Component
public class CurrentCustomerResolver {

    static final Duration TTL = Duration.ofMinutes(5);

    private record Entry(CurrentCustomer customer, Instant expiresAt) {}

    private final OnboardingClient onboarding;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public CurrentCustomerResolver(OnboardingClient onboarding) {
        this(onboarding, Clock.systemUTC());
    }

    CurrentCustomerResolver(OnboardingClient onboarding, Clock clock) {
        this.onboarding = onboarding;
        this.clock = clock;
    }

    /** Cliente aprovado do token atual; senão {@link OnboardingPendingException} (409). */
    public CurrentCustomer require() {
        CurrentCustomer customer = resolve();
        if (!customer.aprovado()) {
            throw new OnboardingPendingException(customer.onboardingStatus());
        }
        return customer;
    }

    /** {@code customer_id} do cliente aprovado do token atual. */
    public Long requireCustomerId() {
        return require().customerId();
    }

    public CurrentCustomer resolve() {
        String sub = currentSub();
        Instant now = clock.instant();
        Entry cached = cache.get(sub);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.customer();
        }
        OnboardingClient.MeResponse me = onboarding.me();
        String status = me.onboardingStatus() == null ? "NAO_INICIADO" : me.onboardingStatus();
        CurrentCustomer customer = new CurrentCustomer(sub, me.customerId(), status);
        if (customer.aprovado()) {
            cache.put(sub, new Entry(customer, now.plus(TTL)));
        } else {
            cache.remove(sub);
        }
        return customer;
    }

    private static String currentSub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Rota /me sem usuário autenticado");
        }
        return auth.getName();
    }
}
