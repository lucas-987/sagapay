package dev.treyer.sagapay.ledger.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * The gRPC API admits a service token only: the realm's token (the decoder every check of the
 * ledger shares), issued for the ledger and carrying its write scope. The audience and the scope
 * are rules of authorisation rather than of the decoder because the REST API takes the realm's
 * tokens of users too, which name no audience of the ledger.
 */
@Configuration
@Profile("!local-noauth")
@EnableConfigurationProperties(GrpcAuthProperties.class)
class GrpcAuthConfig {

    // The prefix Spring Security gives the scopes of a token when it maps them to authorities.
    private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

    @Bean
    @GlobalServerInterceptor
    // After the observation interceptor (order 0), so that refused calls are traced as well.
    @Order(100)
    AuthenticationProcessInterceptor grpcServiceTokenInterceptor(GrpcSecurity grpc, GrpcAuthProperties properties)
            throws Exception {
        AuthorizationManager<Object> scope =
                AuthorityAuthorizationManager.hasAuthority(SCOPE_AUTHORITY_PREFIX + properties.scope());
        AuthorizationManager<Object> audience = (authentication, call) -> new AuthorizationDecision(
                authentication.get() instanceof JwtAuthenticationToken token
                        && token.getToken().getAudience() != null
                        && token.getToken().getAudience().contains(properties.audience()));
        return grpc.authorizeRequests(requests -> requests.allRequests().access(AuthorizationManagers.allOf(scope, audience)))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(withDefaults()))
                .build();
    }
}
