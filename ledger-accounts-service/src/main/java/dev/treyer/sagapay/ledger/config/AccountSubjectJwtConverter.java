package dev.treyer.sagapay.ledger.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.UUID;

/**
 * Refuses, as unauthenticated, a token whose subject is not an account id: the wallet
 * endpoints read the account from it. Only REST applies this, since a service token on gRPC
 * names a client rather than an account.
 */
class AccountSubjectJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("the token's subject is not an account id", e);
        }
        return delegate.convert(jwt);
    }
}
