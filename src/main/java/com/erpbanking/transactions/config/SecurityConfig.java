package com.erpbanking.transactions.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
    public static final String READ_ACCOUNTS = "read:accounts";
    public static final String WRITE_ACCOUNTS = "write:accounts";
    public static final String READ_CREDITS = "read:credits";
    public static final String WRITE_CREDITS = "write:credits";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/accounts/**").hasAuthority(READ_ACCOUNTS)
                        .requestMatchers(HttpMethod.POST, "/accounts", "/transactions").hasAuthority(WRITE_ACCOUNTS)
                        .requestMatchers(HttpMethod.GET, "/credits/**").hasAuthority(READ_CREDITS)
                        .requestMatchers(HttpMethod.POST, "/credits").hasAuthority(WRITE_CREDITS)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::permissions);
        return converter;
    }

    /** Auth0 RBAC envia a claim `permissions`; tokens machine-to-machine trazem as permissões em `scope`. */
    static Collection<GrantedAuthority> permissions(Jwt jwt) {
        Set<String> granted = new LinkedHashSet<>();
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions != null) {
            granted.addAll(permissions);
        }
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            granted.addAll(Arrays.asList(scope.trim().split("\\s+")));
        }
        return granted.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${auth0.domain}") String domain,
                          @Value("${auth0.audience}") String audience) {
        if (domain.isBlank() || audience.isBlank()) {
            throw new IllegalArgumentException("AUTH0_DOMAIN and AUTH0_AUDIENCE are required");
        }
        String issuer = "https://" + domain + "/";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + ".well-known/jwks.json")
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), requiredClaims(audience)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> requiredClaims(String audience) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null
                && jwt.getSubject() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing or invalid claims", null));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.origins:http://localhost:5173}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
