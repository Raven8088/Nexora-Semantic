package com.nexora.semantic.api.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/** JWT authentication enriched with the Semantic principal (type, tenant key, permissions). */
public class SemanticAuthenticationToken extends JwtAuthenticationToken {

    private final transient AuthenticatedPrincipal principal;

    public SemanticAuthenticationToken(Jwt jwt, AuthenticatedPrincipal principal,
                                       Collection<? extends GrantedAuthority> authorities) {
        super(jwt, authorities, principal.subject());
        this.principal = principal;
    }

    public AuthenticatedPrincipal semanticPrincipal() {
        return principal;
    }
}
