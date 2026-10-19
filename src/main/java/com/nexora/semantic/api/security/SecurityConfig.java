package com.nexora.semantic.api.security;

import tools.jackson.databind.ObjectMapper;
import com.nexora.semantic.api.error.ErrorCode;
import com.nexora.semantic.api.error.Problems;
import com.nexora.semantic.api.web.CorrelationIdFilter;
import com.nexora.semantic.application.tenant.TenantService;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Stateless OAuth2 resource server. The JWT decoder is configured by standard Spring Boot properties
 * ({@code spring.security.oauth2.resourceserver.jwt.*}) for any OIDC provider; {@code issuer-uri} is mandatory and
 * without it the application does not start. Authorization is permission-based ({@code @PreAuthorize});
 * everything outside {@code /api/**} and the health probes is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtPrincipalConverter principalConverter,
                                            AuthenticationEntryPoint entryPoint,
                                            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(principalConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Mandatory issuer validation (audit finding I4). Spring Boot only checks {@code iss} when the decoder is built
     * from {@code issuer-uri}; this validator is added by Boot to every decoder it creates (issuer-uri, jwk-set-uri
     * or public key), so the issuer is always enforced. Startup fails if no issuer is configured.
     */
    @Bean
    OAuth2TokenValidator<Jwt> mandatoryIssuerValidator(OAuth2ResourceServerProperties properties) {
        return issuerValidator(properties.getJwt().getIssuerUri());
    }

    static OAuth2TokenValidator<Jwt> issuerValidator(String issuerUri) {
        if (!StringUtils.hasText(issuerUri)) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri is required: tokens must have a validated issuer");
        }
        return new JwtIssuerValidator(issuerUri);
    }

    @Bean
    JwtPrincipalConverter jwtPrincipalConverter(SecurityProperties properties) {
        return new JwtPrincipalConverter(properties);
    }

    /** 401 as RFC 7807. The challenge never includes token error descriptions. */
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, exception instanceof InvalidBearerTokenException
                    || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                    ? "Bearer error=\"invalid_token\"" : "Bearer");
            Problems.write(response, Problems.of(ErrorCode.UNAUTHORIZED, request), objectMapper);
        };
    }

    /** 403 raised by the filter chain (method-level denials are handled by the exception handler). */
    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) ->
                Problems.write(response, Problems.of(ErrorCode.FORBIDDEN, request), objectMapper);
    }

    /** Before Spring Security, so that authentication failures also carry the correlation id. */
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    WebMvcConfigurer callerArgumentResolverConfigurer(TenantService tenantService) {
        HandlerMethodArgumentResolver resolver = new CallerArgumentResolver(tenantService);
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(resolver);
            }
        };
    }
}
