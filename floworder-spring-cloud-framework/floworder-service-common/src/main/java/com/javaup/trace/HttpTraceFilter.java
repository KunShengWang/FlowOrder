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

import static com.javaup.trace.TraceConstant.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // 把链路日志存入当前线程请求，当前请求执行结束之后会移除当前线程中的链路日志
        String traceId = getOrCreateTraceId(request);
        String requestId = getRequestId(request);
        MDC.put(TRACE_ID,traceId);
        if(StringUtils.hasText(requestId)){
            MDC.put(REQUEST_ID,requestId);
        }

        // 把链路日志反馈给调用方，比如apifox调用接口可以从返回值的header中拿结果
        response.setHeader(TRACE_ID_HEADER,traceId);
        if(StringUtils.hasText(requestId)){
            response.setHeader(REQUEST_ID_HEADER,requestId);
        }

        try{
            filterChain.doFilter(request,response);
        }finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    /**
     * 获取TraceId
     */
    private String getOrCreateTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if(StringUtils.hasText(traceId)){
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-","");
    }

    /**
     * 获取requestId
     */
    private String getRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (StringUtils.hasText(requestId)) {
            return requestId;
        }
        return request.getParameter(REQUEST_ID);
    }
}
