package com.erpbanking.transactions.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.erpbanking.transactions.config.SecurityConfig;
import com.erpbanking.transactions.exception.GlobalExceptionHandler;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeSecurityTest {

    @Autowired MockMvc mockMvc;

    @Test
    void getMeExigeReadSelf() throws Exception {
        mockMvc.perform(get("/me/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/me/overview").with(jwt().authorities(
                new SimpleGrantedAuthority(SecurityConfig.READ_ACCOUNTS)))).andExpect(status().isForbidden());
    }

    @Test
    void postMeExigeWriteSelf() throws Exception {
        mockMvc.perform(post("/me/payments").with(jwt().authorities(
                new SimpleGrantedAuthority(SecurityConfig.READ_SELF)))).andExpect(status().isForbidden());
    }

    @Test
    void outrosMetodosEmMeSaoNegados() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/me/payments/1")
                .with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SELF),
                        new SimpleGrantedAuthority(SecurityConfig.WRITE_SELF)))).andExpect(status().isForbidden());
    }

    @Test
    void onboardingPendenteVira409ComStatus() {
        var resp = new GlobalExceptionHandler().onboardingPending(new OnboardingPendingException("EM_ANALISE"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("error", "ONBOARDING_PENDENTE").containsEntry("status", "EM_ANALISE");
    }

    @Test
    void onboardingForaVira503() {
        var resp = new GlobalExceptionHandler().onboardingUnavailable(new OnboardingUnavailableException("x", null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).containsEntry("error", "ONBOARDING_INDISPONIVEL");
    }
}
