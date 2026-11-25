package com.aieng.productbackend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes the 401 ProblemDetail with a Basic challenge (SECURITY-S04, S05, S07). Used both for anonymous callers on
 * protected paths and for bad Basic credentials on any path, so the body never says which part was wrong.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String CHALLENGE = "Basic realm=\"Realm\"";

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        ProblemResponses.write(request, response, objectMapper,
                HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required");
    }
}
