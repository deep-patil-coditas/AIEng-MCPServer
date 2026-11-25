package com.aieng.productbackend.product;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RFC 7807 ProblemDetail for every error this module returns (spec section 6, PRODUCT-S11..S19). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_TITLE = "Validation failed";
    private static final String VALIDATION_DETAIL = "Request has invalid fields";
    private static final Set<Class<?>> INTEGER_TYPES =
            Set.of(Integer.class, int.class, Long.class, long.class, Short.class, short.class, BigInteger.class);
    private static final Set<Class<?>> NUMBER_TYPES =
            Set.of(BigDecimal.class, Double.class, double.class, Float.class, float.class);

    /** PRODUCT-S11: Bean Validation failure, every invalid field, one message each. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return validationFailed(ex, errors, headers, request);
    }

    /** PRODUCT-S12 (wrong JSON type for a field) and PRODUCT-S16 (malformed or missing body). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getCause() instanceof MismatchedInputException mismatch) {
            String field = fieldName(mismatch.getPath());
            if (field != null) {
                return validationFailed(ex, Map.of(field, typeMessage(mismatch.getTargetType())), headers, request);
            }
        }
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or not valid JSON");
        body.setTitle("Malformed request");
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** PRODUCT-S15: non-numeric or out-of-range path id. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid value '" + ex.getValue() + "' for parameter '" + name + "'");
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** PRODUCT-S14: unknown id. */
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(ProductNotFoundException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    /** PRODUCT-S19: anything unexpected. Logged here; never leaked to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error handling {}", request.getDescription(false), ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** PRODUCT-S18: every ProblemDetail (including Spring's 405/415) carries the request path as {@code instance}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationFailed(
            Exception ex, Map<String, String> errors, HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, VALIDATION_DETAIL);
        body.setTitle(VALIDATION_TITLE);
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Nullable
    private static String fieldName(List<JsonMappingException.Reference> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return path.get(path.size() - 1).getFieldName();
    }

    private static String typeMessage(@Nullable Class<?> targetType) {
        if (targetType != null && INTEGER_TYPES.contains(targetType)) {
            return "must be an integer";
        }
        if (targetType != null && NUMBER_TYPES.contains(targetType)) {
            return "must be a number";
        }
        return "must be a string";
    }
}
