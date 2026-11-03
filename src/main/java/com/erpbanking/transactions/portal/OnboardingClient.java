package com.erpbanking.transactions.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Chama o Onboarding {@code GET /me} repassando o token do usuário (mesmo RestTemplate do Score). */
@Component
public class OnboardingClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeResponse(@JsonProperty("customer_id") Long customerId,
                             @JsonProperty("onboarding_status") String onboardingStatus) {}

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public OnboardingClient(RestTemplate creditScoreRestTemplate,
                            @Value("${app.onboarding.base-url:http://localhost:8081}") String baseUrl) {
        this.restTemplate = creditScoreRestTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public MeResponse me() {
        MeResponse resp;
        try {
            resp = restTemplate.getForObject(baseUrl + "/me", MeResponse.class);
        } catch (RestClientException e) {
            throw new OnboardingUnavailableException("Falha ao consultar o Onboarding GET /me", e);
        }
        if (resp == null) {
            throw new OnboardingUnavailableException("Onboarding GET /me sem corpo", null);
        }
        return resp;
    }
}
