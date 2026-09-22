package dev.treyer.sagapay.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Disables Spring Security for local development — active only under the {@code
 * local-noauth} profile, never by default. Same pattern as the ledger's {@code
 * NoAuthSecurityConfig} (ADR 0004), replicated here as soon as this service has
 * REST endpoints to unlock, rather than rediscovering the same 401-by-default
 * via a manual curl.
 */
@Configuration
@Profile("local-noauth")
class NoAuthSecurityConfig {

    @Bean
    SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
