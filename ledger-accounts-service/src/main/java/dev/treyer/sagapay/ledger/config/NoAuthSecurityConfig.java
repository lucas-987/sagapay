package dev.treyer.sagapay.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Disables Spring Security for local development — active only under the {@code
 * local-noauth} profile, never by default. Without this bean,
 * spring-boot-starter-security-oauth2-resource-server being present with no
 * {@code SecurityFilterChain} defined, Spring Security locks everything down by
 * default (generated Basic Auth). Outside this profile, that locked-down default
 * stays — deliberate, not an oversight.
 *
 * <p>Kept distinct from the {@code local} profile ({@code LocalAccountSeeder}) on
 * purpose: each is independently activatable.
 *
 * <p>Stateless on purpose, not Spring Security's default session/form-oriented
 * behavior: a REST API that will carry a bearer JWT later needs neither a session
 * nor CSRF (CSRF protects a session cookie automatically sent by the browser —
 * not relevant without one).
 *
 * <p>Covers REST only — {@code oauth2-resource-server} is a Servlet mechanism, it
 * doesn't apply to the gRPC server. gRPC gets its own separate lock, {@code
 * GrpcDenyByDefaultInterceptor}. Real JWT authentication for either transport is
 * still deferred until Keycloak is wired; no token exists to validate before then.
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
