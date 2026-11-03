package com.erpbanking.transactions.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiConfigTest {
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;

    @Test
    void apiDocsArePublicAndDescribeBearerAuth() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ErpIa - Transactions API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.scheme").value("bearer"))
                .andExpect(content().string(containsString("/credits")));
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    /**
     * Atrás da Vercel (/api/transactions → gateway /transactions → app /) nenhum header revela o prefixo /api,
     * então o server e as URLs do Swagger UI são relativos e o navegador os resolve contra o caminho usado.
     */
    @Test
    void serverUrlIsRelativeToTheApiDocsLocation() throws Exception {
        mvc.perform(get("/v3/api-docs").header("X-Forwarded-Prefix", "/transactions")
                        .header("X-Forwarded-Host", "3.235.6.136").header("X-Forwarded-Proto", "http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers.length()").value(1))
                .andExpect(jsonPath("$.servers[0].url").value(".."));
    }

    @Test
    void swaggerUiConfigUrlsAreRelative() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config").header("X-Forwarded-Prefix", "/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configUrl").value("../v3/api-docs/swagger-config"))
                .andExpect(jsonPath("$.url").value("../v3/api-docs"));
        mvc.perform(get("/swagger-ui/swagger-initializer.js").header("X-Forwarded-Prefix", "/transactions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"configUrl\" : \"../v3/api-docs/swagger-config\"")));
    }
}
