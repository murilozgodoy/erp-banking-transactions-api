package com.erpbanking.transactions.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.erpbanking.transactions.dto.ScoreResponse;
import com.erpbanking.transactions.service.client.CreditScoreClient;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControllersMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean CreditScoreClient creditScoreClient;

    @Test
    void healthReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("transactions-api"));
    }

    @Test
    void createAccountAndGet() throws Exception {
        String created = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"saldoInicial\":100.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();

        Long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(get("/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(1));
    }

    @Test
    void getAccountNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/accounts/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createAccountInvalidReturns400() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":null,\"saldoInicial\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransactionCreditIncreasesBalance() throws Exception {
        String acc = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":2,\"saldoInicial\":50.00}"))
                .andReturn().getResponse().getContentAsString();
        Long id = Long.parseLong(acc.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + id + ",\"tipo\":\"CREDIT\",\"valor\":25.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAtual").value(75.00));
    }

    @Test
    void debitWithInsufficientFundsReturns422() throws Exception {
        String acc = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":3,\"saldoInicial\":10.00}"))
                .andReturn().getResponse().getContentAsString();
        Long id = Long.parseLong(acc.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + id + ",\"tipo\":\"DEBIT\",\"valor\":100.00}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transactionOnMissingAccountReturns404() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":999999,\"tipo\":\"CREDIT\",\"valor\":10.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void solicitarCreditoAprovadoQuandoScoreBaixo() throws Exception {
        when(creditScoreClient.getLatestScore(any()))
                .thenReturn(Optional.of(new ScoreResponse(10L, 850.0, "BAIXO")));

        String body = mockMvc.perform(post("/credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":10,\"valor\":5000.00,\"prazoMeses\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andReturn().getResponse().getContentAsString();

        Long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(get("/credits/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.valorAprovado").value(5000.00));
    }

    @Test
    void solicitarCreditoNegadoSemScore() throws Exception {
        when(creditScoreClient.getLatestScore(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":11,\"valor\":1000.00,\"prazoMeses\":6}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEGADO"))
                .andExpect(jsonPath("$.motivo", containsString("sem score")));
    }

    @Test
    void consultarCreditoInexistenteReturns404() throws Exception {
        mockMvc.perform(get("/credits/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void solicitarCreditoValidationFail() throws Exception {
        mockMvc.perform(post("/credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"valor\":-1,\"prazoMeses\":0}"))
                .andExpect(status().isBadRequest());
    }
}
