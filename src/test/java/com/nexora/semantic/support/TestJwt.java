package com.nexora.semantic.support;

import com.nexora.semantic.domain.tenant.Tenant;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issues real RS256-signed tokens whose public key is published by {@link TestOidcProvider}. By default tokens
 * carry the test issuer, the expected audience, a subject, a principal type and (when given) a tenant UUID.
 */
public final class TestJwt {

    public static final String AUDIENCE = "nexora-semantic";

    private static final RSAKey TRUSTED_KEY = generate("trusted");
    private static final RSAKey UNTRUSTED_KEY = generate("untrusted");

    private TestJwt() {
    }

    /** JWKS document with the trusted public key. */
    static String jwks() {
        return new JWKSet(TRUSTED_KEY.toPublicJWK()).toString();
    }

    /** Human user of {@code tenant} with the given roles. */
    public static Builder human(Tenant tenant, String... roles) {
        return principal("HUMAN", tenant.id().toString(), roles);
    }

    /**
     * Principal of the given type; {@code tenantClaim} is the raw tenant claim value (null omits the claim), so
     * tests can also send malformed values.
     */
    public static Builder principal(String type, String tenantClaim, String... roles) {
        return new Builder().subject(type.toLowerCase() + "-" + System.nanoTime()).tenant(tenantClaim).type(type)
                .roles(roles);
    }

    /** Platform administrator without tenant claim. */
    public static Builder platformAdmin() {
        return principal("HUMAN", null, "PLATFORM_ADMIN");
    }

    public static final class Builder {
        private final Map<String, Object> claims = new LinkedHashMap<>();
        private final Set<String> omitted = new HashSet<>();
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private RSAKey key = TRUSTED_KEY;

        private Builder() {
            claims.put("iss", TestOidcProvider.issuer());
            claims.put("aud", AUDIENCE);
        }

        public Builder subject(String subject) {
            return claim("sub", subject);
        }

        public Builder tenant(String tenantClaim) {
            return claim("tenant", tenantClaim);
        }

        public Builder type(String principalType) {
            return claim("principal_type", principalType);
        }

        public Builder roles(String... roles) {
            return claim("roles", List.of(roles));
        }

        public Builder audience(String audience) {
            return claim("aud", audience);
        }

        public Builder issuer(String issuer) {
            return claim("iss", issuer);
        }

        public Builder claim(String name, Object value) {
            if (value == null) {
                claims.remove(name);
            } else {
                claims.put(name, value);
            }
            return this;
        }

        public Builder without(String claim) {
            omitted.add(claim);
            return this;
        }

        public Builder expiredAgo(Duration ago) {
            this.expiresAt = Instant.now().minus(ago);
            return this;
        }

        public Builder signedWithUntrustedKey() {
            this.key = UNTRUSTED_KEY;
            return this;
        }

        public String subjectValue() {
            return (String) claims.get("sub");
        }

        public String sign() {
            JWTClaimsSet.Builder set = new JWTClaimsSet.Builder()
                    .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(10))))
                    .expirationTime(Date.from(expiresAt));
            claims.forEach((name, value) -> {
                if (!omitted.contains(name)) {
                    set.claim(name, value);
                }
            });
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        set.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Value for the {@code Authorization} header. */
        public String bearer() {
            return "Bearer " + sign();
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
