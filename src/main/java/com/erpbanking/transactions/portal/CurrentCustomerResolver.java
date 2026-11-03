package com.erpbanking.transactions.portal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Descobre o {@code customer_id} e o membro da empresa do token pelo Onboarding {@code GET /me} (token relay).
 * Cache em memória de 60 s por {@code sub}, só para clientes APROVADO: quem ainda está em análise consulta de novo
 * a cada chamada, para o portal liberar assim que a aprovação sair. Uma permissão alterada (ou um membro suspenso)
 * pelo DONO vale em no máximo 60 s.
 *
 * <p>Sem {@code membro} na resposta (Onboarding ainda sem a equipe), o dono do token é tratado como DONO, com
 * todas as permissões e sem limite de pagamento.
 */
@Component
public class CurrentCustomerResolver {

    static final Duration TTL = Duration.ofSeconds(60);

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

    /**
     * Cliente aprovado cujo membro tem a permissão; senão {@link PermissionDeniedException}
     * (403 {@code SEM_PERMISSAO}).
     */
    public CurrentCustomer requirePermission(String permissao) {
        CurrentCustomer customer = require();
        if (!customer.tem(permissao)) {
            throw new PermissionDeniedException(permissao);
        }
        return customer;
    }

    public CurrentCustomer resolve() {
        String sub = currentSub();
        Instant now = clock.instant();
        Entry cached = cache.get(sub);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.customer();
        }
        OnboardingClient.MeResponse me;
        try {
            me = onboarding.me();
        } catch (MemberSuspendedException e) {
            cache.remove(sub);
            throw e;
        }
        String status = me.onboardingStatus() == null ? "NAO_INICIADO" : me.onboardingStatus();
        Membro membro = toMembro(me);
        if (Membro.SUSPENSO.equals(membro.status())) {
            cache.remove(sub);
            throw new MemberSuspendedException();
        }
        CurrentCustomer customer = new CurrentCustomer(sub, me.customerId(), status, membro);
        if (customer.aprovado()) {
            cache.put(sub, new Entry(customer, now.plus(TTL)));
        } else {
            cache.remove(sub);
        }
        return customer;
    }

    private static Membro toMembro(OnboardingClient.MeResponse me) {
        OnboardingClient.MembroResponse m = me.membro();
        if (m == null) {
            return Membro.donoImplicito(blankToNull(me.nome()));
        }
        String nome = blankToNull(m.nome()) != null ? m.nome().trim() : blankToNull(me.nome());
        return new Membro(m.id(), m.papel(), m.permissoes() == null ? null : new HashSet<>(m.permissoes()),
                m.limitePagamento(), m.status(), nome);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String currentSub() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Rota /me sem usuário autenticado");
        }
        return auth.getName();
    }
}
