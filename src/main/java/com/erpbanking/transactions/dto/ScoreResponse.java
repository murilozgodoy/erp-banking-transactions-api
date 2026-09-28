package com.erpbanking.transactions.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreResponse(
        @JsonProperty("customer_id") Long customerId,
        @JsonProperty("score") Double score,
        @JsonProperty("faixa_risco") String faixaRisco
) {}
