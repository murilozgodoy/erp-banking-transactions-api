package com.erpbanking.transactions.portal.credit;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.erpbanking.transactions.config.CreditScoreProperties;
import com.erpbanking.transactions.config.LogSafe;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lê a análise do negócio do próprio cliente no Score ({@code GET /me/analysis}, {@code read:self}) com
 * token relay. Sem análise (404) ou Score fora do ar: vazio, e o portal precifica de forma conservadora.
 */
@Component
public class ScoreAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(ScoreAnalysisClient.class);

    /** Campos da análise usados na precificação. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoreAnalysis(@JsonProperty("score") Double score,
                                @JsonProperty("faixa_risco") String faixaRisco,
                                @JsonProperty("status") String status) {

        public static final String APROVADO = "APROVADO";

        /** Análise concluída e aprovada (não está em análise nem na fila manual). */
        public boolean concluida() {
            return status == null || APROVADO.equals(status);
        }
    }

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ScoreAnalysisClient(RestTemplate creditScoreRestTemplate, CreditScoreProperties props) {
        this.restTemplate = creditScoreRestTemplate;
        String url = props.getBaseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public Optional<ScoreAnalysis> myAnalysis() {
        try {
            ScoreAnalysis analysis = restTemplate.getForObject(baseUrl + "/me/analysis", ScoreAnalysis.class);
            if (analysis == null || analysis.faixaRisco() == null) {
                return Optional.empty();
            }
            return Optional.of(analysis);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Score GET /me/analysis indisponível; seguindo sem análise: {}", LogSafe.describe(e));
            return Optional.empty();
        }
    }
}
