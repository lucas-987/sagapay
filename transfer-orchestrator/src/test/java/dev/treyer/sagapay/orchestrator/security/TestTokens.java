package dev.treyer.sagapay.orchestrator.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Tokens signed with a key generated for the test JVM only: no private key is committed. */
public final class TestTokens {

    /** The issuer the test decoder expects, as the production decoder expects Keycloak's. */
    public static final String ISSUER = "https://issuer.test/realms/sagapay";

    private static final String KEY_ID = "test-key";

    /** The audience and the scope a ledger asks of the service token of its gRPC API. */
    private static final String LEDGER_AUDIENCE = "ledger-accounts-service";

    private static final String LEDGER_SCOPE = "ledger:write";

    private static final KeyPair TEST_KEY = newKey();
    private static final KeyPair OTHER_KEY = newKey();

    private TestTokens() {}

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) TEST_KEY.getPublic();
    }

    /** The key set an identity provider would publish for the test key. */
    public static String jwkSetJson() {
        RSAKey key = new RSAKey.Builder(publicKey())
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new JWKSet(key).toString();
    }

    /** What the orchestrator would fetch with its own credential to call the ledger's gRPC API. */
    public static String ledgerServiceToken() {
        return sign(
                TEST_KEY, "service-account-orchestrator", ISSUER, Instant.now().plusSeconds(300), true);
    }

    public static String forSubject(UUID subject) {
        return forSubject(subject.toString());
    }

    public static String forSubject(String subject) {
        return sign(TEST_KEY, subject, ISSUER, Instant.now().plusSeconds(300), false);
    }

    public static String expiredFor(UUID subject) {
        return sign(TEST_KEY, subject.toString(), ISSUER, Instant.now().minusSeconds(3600), false);
    }

    /** Valid in every way but the key that signed it. */
    public static String signedByAnotherKey(UUID subject) {
        return sign(OTHER_KEY, subject.toString(), ISSUER, Instant.now().plusSeconds(300), false);
    }

    /** Valid in every way but the issuer. */
    public static String fromAnotherIssuer(UUID subject) {
        return sign(
                TEST_KEY,
                subject.toString(),
                "https://other-issuer.test/realms/sagapay",
                Instant.now().plusSeconds(300),
                false);
    }

    private static String sign(
            KeyPair key, String subject, String issuer, Instant expiresAt, boolean forTheLedgerGrpcApi) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .issueTime(Date.from(expiresAt.minusSeconds(7200)))
                    .expirationTime(Date.from(expiresAt));
            if (forTheLedgerGrpcApi) {
                builder.audience(LEDGER_AUDIENCE).claim("scope", "openid " + LEDGER_SCOPE);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), builder.build());
            jwt.sign(new RSASSASigner(key.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair newKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
