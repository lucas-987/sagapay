package dev.treyer.sagapay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Every routed call carries a token. Health is open for the container health check; every other
 * path, the other Actuator endpoints included, is refused to everyone, since any signed-in user
 * would otherwise read the metrics.
 */
@Configuration
public class ResourceServerSecurityConfig {

    @Bean
    SecurityWebFilterChain resourceServer(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .pathMatchers("/v1/**")
                        .authenticated()
                        .anyExchange()
                        .denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
