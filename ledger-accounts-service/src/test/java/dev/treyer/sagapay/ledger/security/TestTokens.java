package dev.treyer.sagapay.ledger.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Tokens signed with keys generated for the test JVM only: no private key is committed. A
 * token is a valid service token until a method of the builder says otherwise.
 */
public final class TestTokens {

    public static final String ISSUER = "https://issuer.test/realms/sagapay";
    public static final String AUDIENCE = "ledger-accounts-service";
    public static final String SCOPE = "ledger:write";
    public static final String KEY_ID = "test-key";

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

    /** The credential of a gRPC client of the ledger: a valid service token. */
    public static ClientInterceptor serviceCredentials() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token().build());
        return MetadataUtils.newAttachHeadersInterceptor(headers);
    }

    public static Builder token() {
        return new Builder();
    }

    public static final class Builder {

        private KeyPair key = TEST_KEY;
        private String issuer = ISSUER;
        private List<String> audience = List.of(AUDIENCE);
        private String scope = "openid " + SCOPE;
        private String keyId = KEY_ID;
        private Instant expiresAt = Instant.now().plusSeconds(300);

        public Builder signedByAnotherKey() {
            this.key = OTHER_KEY;
            return this;
        }

        /** A key id the published key set does not hold. */
        public Builder keyId(String keyId) {
            this.keyId = keyId;
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String... audience) {
            this.audience = List.of(audience);
            return this;
        }

        /** Null leaves the claim out. */
        public Builder scope(String scope) {
            this.scope = scope;
            return this;
        }

        public Builder expired() {
            this.expiresAt = Instant.now().minusSeconds(3600);
            return this;
        }

        public String build() {
            try {
                JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                        .subject("service-account-test")
                        .issuer(issuer)
                        .audience(audience)
                        .issueTime(Date.from(expiresAt.minusSeconds(7200)))
                        .expirationTime(Date.from(expiresAt));
                if (scope != null) {
                    claims.claim("scope", scope);
                }
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims.build());
                jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
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
