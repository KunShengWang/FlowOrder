package com.javaup.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

import static com.javaup.trace.TraceConstant.REQUEST_ID;
import static com.javaup.trace.TraceConstant.REQUEST_ID_HEADER;
import static com.javaup.trace.TraceConstant.TRACE_ID;
import static com.javaup.trace.TraceConstant.TRACE_ID_HEADER;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String traceId = getOrCreateTraceId(request);
        String requestId = getRequestId(request);

        MDC.put(TRACE_ID, traceId);
        if (StringUtils.hasText(requestId)) {
            MDC.put(REQUEST_ID, requestId);
        }

        response.setHeader(TRACE_ID_HEADER, traceId);
        if (StringUtils.hasText(requestId)) {
            response.setHeader(REQUEST_ID_HEADER, requestId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    private String getOrCreateTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String getRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (StringUtils.hasText(requestId)) {
            return requestId;
        }
        return request.getParameter(REQUEST_ID);
    }
}