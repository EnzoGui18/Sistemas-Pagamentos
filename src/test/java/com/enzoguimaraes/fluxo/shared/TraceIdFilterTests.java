package com.enzoguimaraes.fluxo.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class TraceIdFilterTests {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void logsMatchedRouteWithoutPathOrQueryValues(CapturedOutput output) throws Exception {
        var sensitiveValue = "private.user@example.com";
        var request = new MockHttpServletRequest("GET", "/api/v1/clients/" + sensitiveValue);
        request.setQueryString("search=" + sensitiveValue);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                servletRequest.setAttribute(
                        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        "/api/v1/clients/{id}"
                )
        );

        assertThat(output).contains("method=GET route=/api/v1/clients/{id} status=200");
        assertThat(output).doesNotContain(sensitiveValue);
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).hasSize(32);
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_KEY)).isNull();
    }

    @Test
    void marksUnmatchedRoutesWithoutLoggingRawPath(CapturedOutput output) throws Exception {
        var sensitiveValue = "secret@example.com";
        var request = new MockHttpServletRequest("GET", "/unknown/" + sensitiveValue);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((HttpServletRequest) servletRequest).getRequestURI()
        );

        assertThat(output).contains("method=GET route=<unmatched> status=200");
        assertThat(output).doesNotContain(sensitiveValue);
    }
}
