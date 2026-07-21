package com.javaup.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalIncidentScopeAuthorizer {

    public static final String HEADER = "X-FlowOrder-Internal-Token";

    private final String configuredToken;

    public InternalIncidentScopeAuthorizer(
            @Value("${floworder.incident-scope.internal-token:}") String configuredToken) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
    }

    public void authorize(String suppliedToken) {
        if (configuredToken.isBlank() || suppliedToken == null
                || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal incident scope authorization failed");
        }
    }
}
