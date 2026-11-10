package dev.treyer.sagapay.orchestrator.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
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

    private static final KeyPair TEST_KEY = newKey();
    private static final KeyPair OTHER_KEY = newKey();

    private TestTokens() {}

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) TEST_KEY.getPublic();
    }

    public static String forSubject(UUID subject) {
        return forSubject(subject.toString());
    }

    public static String forSubject(String subject) {
        return sign(TEST_KEY, subject, ISSUER, Instant.now().plusSeconds(300));
    }

    public static String expiredFor(UUID subject) {
        return sign(TEST_KEY, subject.toString(), ISSUER, Instant.now().minusSeconds(3600));
    }

    /** Valid in every way but the key that signed it. */
    public static String signedByAnotherKey(UUID subject) {
        return sign(OTHER_KEY, subject.toString(), ISSUER, Instant.now().plusSeconds(300));
    }

    /** Valid in every way but the issuer. */
    public static String fromAnotherIssuer(UUID subject) {
        return sign(
                TEST_KEY,
                subject.toString(),
                "https://other-issuer.test/realms/sagapay",
                Instant.now().plusSeconds(300));
    }

    private static String sign(KeyPair key, String subject, String issuer, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .issueTime(Date.from(expiresAt.minusSeconds(7200)))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
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
