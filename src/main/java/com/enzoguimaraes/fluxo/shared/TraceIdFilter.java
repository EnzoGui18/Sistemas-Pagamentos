package com.enzoguimaraes.fluxo.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceIdFilter.class);

    static final String TRACE_ID_KEY = "traceId";
    static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var traceId = UUID.randomUUID().toString().replace("-", "");
        var startedAt = System.nanoTime();
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            var durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
            var routePattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LOGGER.info(
                    "http_request method={} route={} status={} durationMs={}",
                    request.getMethod(),
                    routePattern == null ? "<unmatched>" : routePattern,
                    response.getStatus(),
                    durationMillis
            );
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
