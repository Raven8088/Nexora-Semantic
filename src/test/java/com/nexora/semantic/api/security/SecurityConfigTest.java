package com.nexora.semantic.api.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Audit finding I4: the issuer is mandatory configuration and is always validated. */
class SecurityConfigTest {

    private static Jwt tokenFrom(String issuer) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        return builder.build();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void startupFailsWithoutIssuer(String issuer) {
        assertThatThrownBy(() -> SecurityConfig.issuerValidator(issuer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri is required");
    }

    @Test
    void validatorAcceptsOnlyTheConfiguredIssuer() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.issuerValidator("https://idp.example/realms/nexora");

        assertThat(validator.validate(tokenFrom("https://idp.example/realms/nexora")).hasErrors()).isFalse();
        assertThat(validator.validate(tokenFrom("https://evil.example/realms/nexora")).hasErrors()).isTrue();
        assertThat(validator.validate(tokenFrom("https://idp.example/realms/nexora/")).hasErrors()).isTrue();
        assertThat(validator.validate(tokenFrom(null)).hasErrors()).isTrue();
    }
}
