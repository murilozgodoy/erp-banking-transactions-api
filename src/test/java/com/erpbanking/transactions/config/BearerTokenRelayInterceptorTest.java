package com.erpbanking.transactions.config;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BearerTokenRelayInterceptorTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new BearerTokenRelayInterceptor());
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithJwt(String tokenValue) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(tokenValue).header("alg", "RS256").subject("auth0|user")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void relaysTheAuthenticatedUserToken() {
        authenticateWithJwt("user-jwt");
        server.expect(requestTo("http://score.test/x"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
                .andRespond(withSuccess());

        restTemplate.getForObject("http://score.test/x", String.class);
        server.verify();
    }

    @Test
    void sendsNoTokenWithoutJwtAuthentication() {
        server.expect(requestTo("http://score.test/anonymous"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess());
        server.expect(requestTo("http://score.test/other-auth"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess());

        restTemplate.getForObject("http://score.test/anonymous", String.class);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "pw"));
        restTemplate.getForObject("http://score.test/other-auth", String.class);
        server.verify();
    }

    @Test
    void keepsAnExplicitAuthorizationHeader() {
        authenticateWithJwt("user-jwt");
        server.expect(requestTo("http://score.test/explicit"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer explicit"))
                .andRespond(withSuccess());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("explicit");
        restTemplate.exchange("http://score.test/explicit", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        server.verify();
    }
}
