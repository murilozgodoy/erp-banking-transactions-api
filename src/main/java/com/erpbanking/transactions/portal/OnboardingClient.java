package com.erpbanking.transactions.portal;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Chama o Onboarding {@code GET /me} repassando o token do usuário (mesmo RestTemplate do Score). */
@Component
public class OnboardingClient {

    /**
     * Resposta do {@code GET /me}. {@code membro} é {@code null} enquanto o Onboarding não devolve a equipe;
     * {@code nome} é o nome da pessoa no topo da resposta (opcional), usado se {@code membro.nome} faltar.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeResponse(@JsonProperty("customer_id") Long customerId,
                             @JsonProperty("onboarding_status") String onboardingStatus,
                             @JsonProperty("membro") MembroResponse membro,
                             @JsonProperty("nome") String nome) {

        public MeResponse(Long customerId, String onboardingStatus) {
            this(customerId, onboardingStatus, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MembroResponse(@JsonProperty("id") Long id,
                                 @JsonProperty("papel") String papel,
                                 @JsonProperty("permissoes") List<String> permissoes,
                                 @JsonProperty("limite_pagamento") BigDecimal limitePagamento,
                                 @JsonProperty("status") String status,
                                 @JsonProperty("nome") String nome) {}

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public OnboardingClient(RestTemplate creditScoreRestTemplate,
                            @Value("${app.onboarding.base-url:http://localhost:8081}") String baseUrl) {
        this.restTemplate = creditScoreRestTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * @throws MemberSuspendedException     403 {@code MEMBRO_SUSPENSO} do Onboarding
     * @throws OnboardingUnavailableException qualquer outra falha
     */
    public MeResponse me() {
        MeResponse resp;
        try {
            resp = restTemplate.getForObject(baseUrl + "/me", MeResponse.class);
        } catch (HttpClientErrorException.Forbidden e) {
            if (e.getResponseBodyAsString().contains(MemberSuspendedException.CODE)) {
                throw new MemberSuspendedException();
            }
            throw new OnboardingUnavailableException("Onboarding GET /me respondeu 403", e);
        } catch (RestClientException e) {
            throw new OnboardingUnavailableException("Falha ao consultar o Onboarding GET /me", e);
        }
        if (resp == null) {
            throw new OnboardingUnavailableException("Onboarding GET /me sem corpo", null);
        }
        return resp;
    }
}
