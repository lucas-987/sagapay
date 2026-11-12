package dev.treyer.sagapay.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every REST call carries a token of the realm. Health is open for the container health checks;
 * the other Actuator endpoints are refused to everyone, since any signed-in user would
 * otherwise read the metrics. Stateless and without CSRF: the token travels in a header that a
 * browser does not attach on its own, and there is no session cookie to protect. gRPC is locked
 * separately, by {@code GrpcAuthConfig}.
 */
@Configuration
class ResourceServerSecurityConfig {

    @Bean
    SecurityFilterChain resourceServer(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/v1/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new AccountSubjectJwtConverter())))
                .build();
    }
}
