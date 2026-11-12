package dev.treyer.sagapay.ledger.adapter.in.rest;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The account of the request's token. A user id is also its account id, and the security
 * chain only lets through a token whose subject is one.
 */
@Component
class CallerAccount {

    UUID id() {
        // The REST chain only lets a JWT through, so the cast holds.
        JwtAuthenticationToken token =
                (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(token.getToken().getSubject());
    }
}
