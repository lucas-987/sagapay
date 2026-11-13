package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/** The caller is the subject of the validated token. A user id is also its account id: the ledger has
 * no separate user entity. */
final class CallerIdentity {

    private CallerIdentity() {}

    static UUID requireUserId() {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication)) {
            throw new UnidentifiedCallerException("no validated token");
        }
        String subject = authentication.getToken().getSubject();
        if (subject == null) {
            throw new UnidentifiedCallerException("token without subject");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new UnidentifiedCallerException("token subject is not an account id", e);
        }
    }
}
