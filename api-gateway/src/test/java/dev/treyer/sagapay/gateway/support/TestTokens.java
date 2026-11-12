package dev.treyer.sagapay.gateway.support;

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

/** Tokens signed with a key generated for the test JVM only: no private key is committed. */
public final class TestTokens {

    public static final String ISSUER = "https://issuer.test/realms/sagapay";

    private static final String KEY_ID = "test-key";
    private static final KeyPair TEST_KEY = newKey();
    private static final KeyPair OTHER_KEY = newKey();

    private TestTokens() {}

    /** The key set an identity provider would publish for the test key. */
    public static String jwkSetJson() {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) TEST_KEY.getPublic())
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new JWKSet(key).toString();
    }

    public static String valid() {
        return sign(TEST_KEY, ISSUER, Instant.now().plusSeconds(300));
    }

    public static String expired() {
        return sign(TEST_KEY, ISSUER, Instant.now().minusSeconds(3600));
    }

    public static String fromAnotherIssuer() {
        return sign(
                TEST_KEY,
                "https://other-issuer.test/realms/sagapay",
                Instant.now().plusSeconds(300));
    }

    public static String withABadSignature() {
        return sign(OTHER_KEY, ISSUER, Instant.now().plusSeconds(300));
    }

    private static String sign(KeyPair key, String issuer, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("5b1f0c1e-7a34-4d5e-9f43-0d6a1f0c9a11")
                    .issuer(issuer)
                    .issueTime(Date.from(expiresAt.minusSeconds(7200)))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
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
