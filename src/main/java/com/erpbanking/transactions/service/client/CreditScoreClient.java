package com.erpbanking.transactions.service.client;

import java.util.Optional;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.erpbanking.transactions.config.CreditScoreProperties;
import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.exception.ApiExceptions.ScoreUnavailableException;

@Component
public class CreditScoreClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CreditScoreClient(RestTemplate creditScoreRestTemplate, CreditScoreProperties props) {
        this.restTemplate = creditScoreRestTemplate;
        this.baseUrl = props.getBaseUrl();
    }

    public Optional<ScoreResponse> getLatestScore(Long customerId) {
        String url = baseUrl + "/scores/customers/" + customerId + "/latest";
        try {
            ScoreResponse resp = restTemplate.getForObject(url, ScoreResponse.class);
            return Optional.ofNullable(resp);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().equals(HttpStatusCode.valueOf(404))) {
                return Optional.empty();
            }
            throw new ScoreUnavailableException("Score API respondeu " + e.getStatusCode());
        } catch (RestClientException e) {
            throw new ScoreUnavailableException("Falha ao contactar Score API: " + e.getMessage());
        }
    }
}
