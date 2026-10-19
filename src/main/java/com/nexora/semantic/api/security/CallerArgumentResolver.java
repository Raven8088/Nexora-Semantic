package com.nexora.semantic.api.security;

import com.nexora.semantic.api.web.CorrelationIdFilter;
import com.nexora.semantic.application.tenant.TenantService;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.TenantId;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies controller parameters describing the caller:
 * <ul>
 *   <li>{@link CallerContext}: requires the token's tenant to exist and be ACTIVE, otherwise
 *       {@link com.nexora.semantic.domain.shared.TenantAccessDeniedException};</li>
 *   <li>{@link PrincipalRef}: the caller only, for platform endpoints that are not tenant-scoped.</li>
 * </ul>
 * The tenant is taken exclusively from the token; controllers never read it from paths or bodies.
 */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private final TenantService tenantService;

    public CallerArgumentResolver(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == CallerContext.class || type == PrincipalRef.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        AuthenticatedPrincipal principal = currentPrincipal();
        if (parameter.getParameterType() == PrincipalRef.class) {
            return principal.toRef();
        }
        TenantId tenantId = tenantService.resolveActiveTenant(principal.tenantId());
        Object correlationId = webRequest.getAttribute(CorrelationIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return new CallerContext(tenantId, principal.toRef(), correlationId instanceof String id ? id : null);
    }

    private static AuthenticatedPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof SemanticAuthenticationToken token) {
            return token.semanticPrincipal();
        }
        throw new AuthenticationCredentialsNotFoundException("No Semantic authentication");
    }
}
