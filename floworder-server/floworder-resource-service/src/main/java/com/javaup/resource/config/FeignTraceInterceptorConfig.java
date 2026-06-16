package com.javaup.resource.config;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import static com.javaup.trace.TraceConstant.REQUEST_ID;
import static com.javaup.trace.TraceConstant.REQUEST_ID_HEADER;
import static com.javaup.trace.TraceConstant.TRACE_ID;
import static com.javaup.trace.TraceConstant.TRACE_ID_HEADER;

@Configuration
public class FeignTraceInterceptorConfig {

    @Bean
    public RequestInterceptor traceRequestInterceptor() {
        return template -> {
            String traceId = MDC.get(TRACE_ID);
            if (StringUtils.hasText(traceId)) {
                template.header(TRACE_ID_HEADER, traceId);
            }

            String requestId = MDC.get(REQUEST_ID);
            if (StringUtils.hasText(requestId)) {
                template.header(REQUEST_ID_HEADER, requestId);
            }
        };
    }
}