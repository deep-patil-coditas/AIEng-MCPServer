package com.aieng.productbackend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;

/**
 * Writes a security-layer ProblemDetail straight to the response (SECURITY-S07). Never calls
 * {@code sendError}, so there is no dispatch to Boot's {@code /error} page and {@code instance} stays the request path.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper objectMapper,
                      HttpStatus status, String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
