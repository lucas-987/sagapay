package dev.treyer.sagapay.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Opens REST for local development only; outside this profile REST requires a valid
 * token of the realm. Stateless and without CSRF: a bearer-token API has no
 * session cookie to protect. gRPC is locked separately by
 * {@code GrpcDenyByDefaultInterceptor}.
 */
@Configuration
@Profile("local-noauth")
class NoAuthSecurityConfig {

    @Bean
    SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Spring Boot puts a token check on gRPC as soon as a {@code JwtDecoder} bean exists and no
     * interceptor of this kind does; this one takes its place, the gRPC counterpart of the
     * permit-all chain. It reads no token, so whatever a caller sends goes unchecked.
     */
    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor grpcPermitAll(GrpcSecurity grpc) throws Exception {
        return grpc.authorizeRequests(requests -> requests.allRequests().permitAll())
                .build();
    }
}
