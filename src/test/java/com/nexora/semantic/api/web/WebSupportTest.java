package com.nexora.semantic.api.web;

import com.nexora.semantic.domain.shared.StaleResourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSupportTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void serverGeneratedIdIsExposedInHeaderAttributeAndMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                mdcDuringChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        });

        String id = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(CorrelationIdFilter.current(request)).isEqualTo(id);
        assertThat(mdcDuringChain.get()).isEqualTo(id);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"req-123", "3fa85f64-5717-4562-b3fc-2c963f66afa6", "bad id", "x\r\nInjected: header"})
    void clientSuppliedRequestIdIsAlwaysIgnored(String supplied) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, supplied);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(supplied).matches("[0-9a-f-]{36}");
        assertThat(CorrelationIdFilter.current(request)).isNotEqualTo(supplied);
    }

    @Test
    void eachRequestGetsADifferentId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), first, new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest(), second, new MockFilterChain());
        assertThat(first.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(second.getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    void etagRoundTrip() {
        assertThat(ETags.of(7)).isEqualTo("\"7\"");
        assertThat(ETags.expectedVersion("\"7\"", "X")).isEqualTo(7);
        assertThat(ETags.expectedVersion(" \"0\" ", "X")).isZero();
    }

    @Test
    void missingIfMatchRequiresPrecondition() {
        assertThatThrownBy(() -> ETags.expectedVersion(null, "X")).isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> ETags.expectedVersion(" ", "X")).isInstanceOf(PreconditionRequiredException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "7", "W/\"7\"", "\"7\", \"8\"", "\"-1\"", "\"abc\"", "\"99999999999999999999\""})
    void unsupportedIfMatchNeverMatches(String ifMatch) {
        assertThatThrownBy(() -> ETags.expectedVersion(ifMatch, "X")).isInstanceOf(StaleResourceException.class);
    }
}
