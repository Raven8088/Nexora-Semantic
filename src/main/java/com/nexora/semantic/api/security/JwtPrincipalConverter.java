package com.nexora.semantic.api.security;

import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Maps a validated JWT to a {@link SemanticAuthenticationToken}: subject, principal type, tenant and the
 * permissions granted by its roles. The token is rejected ({@code invalid_token}, HTTP 401) when:
 * <ul>
 *   <li>the subject is missing or malformed;</li>
 *   <li>the principal type is missing or not exactly one of HUMAN, SERVICE or AI (decision D2);</li>
 *   <li>the tenant claim is present but is not a canonical UUID;</li>
 *   <li>the tenant claim is absent and the token does not carry {@link Role#PLATFORM_ADMIN}: only platform
 *       operators, who manage tenants, may act without a tenant.</li>
 * </ul>
 * Defense in depth, independent of how roles are assigned in the identity provider: non-human principals never
 * receive {@link Permission#PROPOSAL_APPROVE}, and AI principals are limited to {@link #AI_ALLOWED}.
 */
public class JwtPrincipalConverter implements Converter<Jwt, JwtAuthenticationToken> {

    static final Set<Permission> AI_ALLOWED = EnumSet.of(Permission.CATALOG_READ, Permission.PROPOSAL_WRITE);

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final SecurityProperties properties;

    public JwtPrincipalConverter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > PrincipalRef.MAX_ID_LENGTH
                || subject.chars().anyMatch(Character::isISOControl)) {
            throw invalidToken();
        }
        PrincipalType type = principalType(claim(jwt.getClaims(), properties.principalTypeClaim()));
        Set<Role> roles = rolesOf(claim(jwt.getClaims(), properties.rolesClaim()));
        TenantId tenantId = tenantId(claim(jwt.getClaims(), properties.tenantClaim()));
        if (tenantId == null && !roles.contains(Role.PLATFORM_ADMIN)) {
            throw invalidToken();
        }
        Set<Permission> permissions = restrict(type, permissionsOf(roles));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(subject, type, tenantId, permissions);
        List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(p -> new SimpleGrantedAuthority(p.authority()))
                .toList();
        return new SemanticAuthenticationToken(jwt, principal, authorities);
    }

    /** Exactly HUMAN, SERVICE or AI; anything else, including absence, invalidates the token. */
    private static PrincipalType principalType(Object value) {
        if (value instanceof String text) {
            for (PrincipalType type : PrincipalType.values()) {
                if (type.name().equals(text)) {
                    return type;
                }
            }
        }
        throw invalidToken();
    }

    /** Null when the claim is absent; a present claim must be a canonical UUID string. */
    private static TenantId tenantId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text && CANONICAL_UUID.matcher(text).matches()) {
            return new TenantId(UUID.fromString(text));
        }
        throw invalidToken();
    }

    private static Set<Role> rolesOf(Object claim) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (claim instanceof Collection<?> names) {
            names.forEach(name -> addRole(roles, name));
        } else {
            addRole(roles, claim);
        }
        return roles;
    }

    private static void addRole(Set<Role> roles, Object name) {
        if (name instanceof String text) {
            Role.fromName(text).ifPresent(roles::add);
        }
    }

    private static Set<Permission> permissionsOf(Set<Role> roles) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        roles.forEach(role -> permissions.addAll(role.permissions()));
        return permissions;
    }

    private static Set<Permission> restrict(PrincipalType type, Set<Permission> granted) {
        Set<Permission> effective = EnumSet.noneOf(Permission.class);
        effective.addAll(granted);
        if (type != PrincipalType.HUMAN) {
            effective.remove(Permission.PROPOSAL_APPROVE);
        }
        if (type == PrincipalType.AI) {
            effective.retainAll(AI_ALLOWED);
        }
        return effective;
    }

    /** Resolves a possibly dotted claim path; returns null when any segment is missing. */
    private static Object claim(Map<String, Object> claims, String path) {
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN));
    }
}
