package com.erpbanking.transactions.portal.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.erpbanking.transactions.config.CreditScoreProperties;

class ScoreAnalysisClientTest {

    RestTemplate rest;
    MockRestServiceServer server;
    ScoreAnalysisClient client;

    @BeforeEach
    void setUp() {
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        CreditScoreProperties props = new CreditScoreProperties();
        props.setBaseUrl("http://score/");
        client = new ScoreAnalysisClient(rest, props);
    }

    @Test
    void leScoreFaixaEStatus() {
        server.expect(requestTo("http://score/me/analysis")).andRespond(withSuccess(
                "{\"customer_id\":1,\"status\":\"APROVADO\",\"score\":742,\"faixa_risco\":\"MEDIO\",\"kpis\":[]}",
                MediaType.APPLICATION_JSON));
        var a = client.myAnalysis().orElseThrow();
        assertThat(a.score()).isEqualTo(742.0);
        assertThat(a.faixaRisco()).isEqualTo("MEDIO");
        assertThat(a.concluida()).isTrue();
        assertThat(new ScoreAnalysisClient.ScoreAnalysis(1.0, "BAIXO", "ANALISE_MANUAL").concluida()).isFalse();
        assertThat(new ScoreAnalysisClient.ScoreAnalysis(1.0, "BAIXO", null).concluida()).isTrue();
    }

    @Test
    void semFaixaOu404OuErroViraVazio() {
        server.expect(requestTo("http://score/me/analysis")).andRespond(withSuccess(
                "{\"status\":\"EM_ANALISE\"}", MediaType.APPLICATION_JSON));
        assertThat(client.myAnalysis()).isEmpty();
        server.reset();
        server.expect(requestTo("http://score/me/analysis")).andRespond(withResourceNotFound());
        assertThat(client.myAnalysis()).isEmpty();
        server.reset();
        server.expect(requestTo("http://score/me/analysis")).andRespond(withServerError());
        assertThat(client.myAnalysis()).isEmpty();
    }
}
