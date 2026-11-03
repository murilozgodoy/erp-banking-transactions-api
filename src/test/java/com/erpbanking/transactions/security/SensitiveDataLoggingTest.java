package com.erpbanking.transactions.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.erpbanking.transactions.config.CreditScoreProperties;
import com.erpbanking.transactions.config.LogSafe;
import com.erpbanking.transactions.messaging.CustomerCreatedListener;
import com.erpbanking.transactions.metrics.BusinessMetrics;
import com.erpbanking.transactions.portal.OnboardingClient;
import com.erpbanking.transactions.portal.OnboardingUnavailableException;
import com.erpbanking.transactions.portal.credit.ScoreAnalysisClient;
import com.erpbanking.transactions.service.AccountService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Logs e mensagens de erro sem token, CPF/CNPJ completo, e-mail ou payload de evento. */
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataLoggingTest {

    private static final String CNPJ = "12.345.678/0001-95";
    private static final String CPF = "123.456.789-09";
    private static final String EMAIL = "ana@empresa-b.com.br";
    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.segredo.assinatura";

    private static void assertSemDadosSensiveis(String texto) {
        assertThat(texto).doesNotContain(CNPJ).doesNotContain("12345678000195").doesNotContain(CPF)
                .doesNotContain(EMAIL).doesNotContain(TOKEN);
    }

    @Test
    void customerCreatedInvalidoNaoLogaOPayload(CapturedOutput output) {
        CustomerCreatedListener listener = new CustomerCreatedListener(mock(AccountService.class),
                new BusinessMetrics(new SimpleMeterRegistry()));
        Map<String, Object> event = Map.of("event_id", "ev-1", "customer_id", "x", "cpf", CPF, "email", EMAIL,
                "empresa", Map.of("cnpj", CNPJ));
        assertThatThrownBy(() -> listener.onCustomerCreated(event))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(output.getAll()).contains("ev-1");
        assertSemDadosSensiveis(output.getAll());
    }

    @Test
    void logSafeNuncaIncluiCorpoNemCabecalhos() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        HttpServerErrorException e = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", headers,
                ("{\"cnpj\":\"" + CNPJ + "\",\"email\":\"" + EMAIL + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        assertThat(LogSafe.describe(e)).isEqualTo("BadGateway 502");
        assertThat(LogSafe.describe(new ResourceAccessException("I/O " + TOKEN))).isEqualTo("ResourceAccessException");
        assertThat(LogSafe.describe(null)).isEqualTo("null");
    }

    @Test
    void scoreAnalysisIndisponivelNaoLogaOCorpo(CapturedOutput output) {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        CreditScoreProperties props = new CreditScoreProperties();
        props.setBaseUrl("http://score");
        String corpo = "{\"cnpj\":\"" + CNPJ + "\",\"email\":\"" + EMAIL + "\"}";
        server.expect(ExpectedCount.twice(), requestTo("http://score/me/analysis"))
                .andRespond(withServerError().body(corpo).contentType(MediaType.APPLICATION_JSON));
        // O motivo do LogSafe: a mensagem padrão do RestTemplate traz o corpo da resposta.
        assertThatThrownBy(() -> rest.getForObject("http://score/me/analysis", String.class))
                .hasMessageContaining(CNPJ);
        assertThat(new ScoreAnalysisClient(rest, props).myAnalysis()).isEmpty();
        assertThat(output.getAll()).contains("InternalServerError 500");
        assertSemDadosSensiveis(output.getAll());
    }

    /** limite_pagamento não numérico (NaN/Infinity) no GET /me: falha fechada (503), nunca "sem limite". */
    @Test
    void limiteNaoNumericoDoOnboardingFalhaFechado() {
        for (String limite : new String[] {"\"NaN\"", "NaN", "\"Infinity\"", "-Infinity", "\"abc\"", "{}"}) {
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
            server.expect(requestTo("http://onboarding/me")).andRespond(withSuccess("{\"customer_id\":1,"
                    + "\"onboarding_status\":\"APROVADO\",\"membro\":{\"id\":1,\"papel\":\"OPERADOR\","
                    + "\"permissoes\":[\"pagar\"],\"limite_pagamento\":" + limite + ",\"status\":\"ATIVO\"}}",
                    MediaType.APPLICATION_JSON));
            OnboardingClient client = new OnboardingClient(rest, "http://onboarding");
            assertThatThrownBy(client::me).as(limite).isInstanceOf(OnboardingUnavailableException.class);
        }
    }

    @Test
    void onboardingForaNaoExpoeOCorpoNaResposta() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://onboarding/me")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"error\":\"OUTRA_COISA\",\"cnpj\":\"" + CNPJ + "\"}"));
        assertThatThrownBy(new OnboardingClient(rest, "http://onboarding")::me)
                .isInstanceOf(OnboardingUnavailableException.class)
                .satisfies(e -> assertSemDadosSensiveis(e.getMessage()));
    }

    /** A config publicada não loga detalhes de requisição (cabeçalho Authorization) nem com DEBUG. */
    @Test
    void configPublicadaNaoLogaCabecalhos() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties p = yaml.getObject();
        assertThat(p).isNotNull();
        assertThat(p.getProperty("spring.mvc.log-request-details")).isEqualTo("false");
        assertThat(p.getProperty("logging.level.org.springframework.web")).isEqualTo("INFO");
        assertThat(p.getProperty("logging.level.org.springframework.security")).isEqualTo("INFO");
        assertThat(p.stringPropertyNames()).noneMatch(k -> k.startsWith("logging.level")
                && ("DEBUG".equalsIgnoreCase(p.getProperty(k)) || "TRACE".equalsIgnoreCase(p.getProperty(k))));
    }
}
