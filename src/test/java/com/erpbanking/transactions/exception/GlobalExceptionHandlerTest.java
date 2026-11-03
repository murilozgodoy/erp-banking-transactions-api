package com.erpbanking.transactions.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/score-down")
        String scoreDown() {
            throw new ScoreServiceUnavailableException(new RuntimeException("timeout interno"));
        }

        @GetMapping("/score-bad-gateway")
        String scoreBadGateway() {
            throw new ApiExceptions.ScoreUnavailableException("Score API respondeu 500");
        }

        @GetMapping("/score-forbidden")
        String scoreForbidden() {
            throw new ApiExceptions.ScoreForbiddenException("Sem permissão para consultar o score (read:scores)");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void scoreServiceUnavailableReturns503WithPortugueseMessage() throws Exception {
        mvc.perform(get("/score-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error")
                        .value("Serviço de score indisponível no momento. Tente novamente em instantes."));
    }

    @Test
    void scoreUnavailableStillReturns502() throws Exception {
        mvc.perform(get("/score-bad-gateway"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Score API respondeu 500"));
    }

    @Test
    void scoreForbiddenStillReturns403() throws Exception {
        mvc.perform(get("/score-forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Sem permissão para consultar o score (read:scores)"));
    }
}
