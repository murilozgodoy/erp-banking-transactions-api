package com.erpbanking.transactions.config;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;

    @Test
    void publicHealthAndProtectedRoutes() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        for (String path : new String[]{"/accounts/1", "/credits/1", "/transactions", "/actuator/health"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/accounts").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/accounts/999999").with(jwt())).andExpect(status().isNotFound());
    }

    @Test
    void invalidBearerIsUnauthorized() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid token"));
        mvc.perform(get("/accounts/1").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflight() throws Exception {
        mvc.perform(options("/accounts").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/accounts").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void audienceAndExpirationAreRequired() {
        var validator = SecurityConfig.requiredClaims("expected");
        Instant now = Instant.now();
        assertThat(validator.validate(new Jwt("t", now, now.plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("aud", "expected", "sub", "user", "exp", now.plusSeconds(300)))).hasErrors()).isFalse();
        assertThat(validator.validate(new Jwt("t", now, now.plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("aud", "wrong", "sub", "user", "exp", now.plusSeconds(300)))).hasErrors()).isTrue();
        assertThat(validator.validate(new Jwt("t", null, null,
                Map.of("alg", "RS256"), Map.of("aud", "expected", "sub", "user"))).hasErrors()).isTrue();
        assertThat(validator.validate(new Jwt("t", now, now.plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", "user", "exp", now.plusSeconds(300)))).hasErrors()).isTrue();
    }

    @Test
    void decoderVerifiesSignatureIssuerAudienceAndExpiration() throws Exception {
        var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("local-test").generate();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        byte[] jwks = new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var output = exchange.getResponseBody()) { output.write(jwks); }
        });
        server.start();
        try {
            var localDecoder = NimbusJwtDecoder.withJwkSetUri("http://localhost:" + server.getAddress().getPort() + "/jwks").build();
            localDecoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer("https://test.auth0.com/"), SecurityConfig.requiredClaims("expected")));
            Instant now = Instant.now();
            for (int scenario = 0; scenario < 6; scenario++) {
                var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().subject("user")
                        .issuer(scenario == 1 ? "wrong" : "https://test.auth0.com/")
                        .audience(scenario == 2 ? "wrong" : "expected");
                if (scenario != 4) claims.expirationTime(java.util.Date.from(scenario == 3 ? now.minusSeconds(300) : now.plusSeconds(300)));
                var signed = new com.nimbusds.jwt.SignedJWT(
                        new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID("local-test").build(), claims.build());
                var signingKey = scenario == 5 ? new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate() : key;
                signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(signingKey));
                if (scenario == 0) assertThat(localDecoder.decode(signed.serialize()).getSubject()).isEqualTo("user");
                else assertThatThrownBy(() -> localDecoder.decode(signed.serialize())).isInstanceOf(JwtException.class);
            }
        } finally { server.stop(0); }
    }

    @Test
    void decoderConfiguration() {
        var config = new SecurityConfig();
        assertThat(config.jwtDecoder("test.auth0.com", "expected")).isNotNull();
        assertThatThrownBy(() -> config.jwtDecoder("", "expected")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.jwtDecoder("test.auth0.com", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
