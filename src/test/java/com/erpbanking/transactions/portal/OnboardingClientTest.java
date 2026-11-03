package com.erpbanking.transactions.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class OnboardingClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final OnboardingClient client = new OnboardingClient(restTemplate, "http://onboarding:8081/");

    @Test
    void leCustomerIdEStatusDoMe() {
        server.expect(requestTo("http://onboarding:8081/me")).andRespond(withSuccess(
                "{\"customer_id\":42,\"onboarding_status\":\"APROVADO\",\"empresa\":{\"cnpj\":\"1\"}}",
                MediaType.APPLICATION_JSON));

        OnboardingClient.MeResponse me = client.me();

        assertThat(me.customerId()).isEqualTo(42L);
        assertThat(me.onboardingStatus()).isEqualTo("APROVADO");
    }

    @Test
    void leOMembro() {
        server.expect(requestTo("http://onboarding:8081/me")).andRespond(withSuccess(
                "{\"customer_id\":42,\"onboarding_status\":\"APROVADO\",\"nome\":\"Ana\",\"membro\":{\"id\":5,"
                        + "\"papel\":\"OPERADOR\",\"permissoes\":[\"ver_financeiro\",\"pagar\"],"
                        + "\"limite_pagamento\":500.5,\"status\":\"ATIVO\",\"nome\":\"Otávio\",\"extra\":1}}",
                MediaType.APPLICATION_JSON));

        OnboardingClient.MeResponse me = client.me();

        assertThat(me.nome()).isEqualTo("Ana");
        assertThat(me.membro().id()).isEqualTo(5L);
        assertThat(me.membro().papel()).isEqualTo("OPERADOR");
        assertThat(me.membro().permissoes()).containsExactly("ver_financeiro", "pagar");
        assertThat(me.membro().limitePagamento()).isEqualByComparingTo("500.5");
        assertThat(me.membro().status()).isEqualTo("ATIVO");
        assertThat(me.membro().nome()).isEqualTo("Otávio");
    }

    @Test
    void membroSuspensoVira403MembroSuspenso() {
        server.expect(requestTo("http://onboarding:8081/me")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"MEMBRO_SUSPENSO\"}"));

        assertThatThrownBy(client::me).isInstanceOf(MemberSuspendedException.class);
    }

    @Test
    void outro403ViraIndisponivel() {
        server.expect(requestTo("http://onboarding:8081/me")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(client::me).isInstanceOf(OnboardingUnavailableException.class);
    }

    @Test
    void erroDoOnboardingViraIndisponivel() {
        server.expect(requestTo("http://onboarding:8081/me")).andRespond(withServerError());

        assertThatThrownBy(client::me).isInstanceOf(OnboardingUnavailableException.class);
    }

    @Test
    void corpoVazioViraIndisponivel() {
        server.expect(requestTo("http://onboarding:8081/me")).andRespond(withSuccess());

        assertThatThrownBy(client::me).isInstanceOf(OnboardingUnavailableException.class);
    }
}
