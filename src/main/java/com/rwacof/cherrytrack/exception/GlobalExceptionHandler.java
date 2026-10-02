package com.rwacof.cherrytrack.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/** Centralised mapping of exceptions to RFC 7807 problem responses. Never leaks internals. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldError(String field, String message) {}

    public static ProblemDetail problem(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        pd.setProperty("message", message);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            pd.setProperty("fieldErrors", fieldErrors);
        }
        return pd;
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(problem(status, code, message, null));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException ex) {
        List<FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.add(new FieldError(fe.getField(), fe.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.add(new FieldError(ge.getObjectName(), ge.getDefaultMessage())));
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> errors = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            errors.add(new FieldError(v.getPropertyPath().toString(), v.getMessage()));
        }
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.", errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleMalformed(Exception ex) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request is malformed or has invalid values.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge(MaxUploadSizeExceededException ex) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "That file is too large. Images can be up to 2 MB.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return respond(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "This record was modified by someone else. Refresh and try again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, "DATA_INTEGRITY",
                "The change conflicts with existing data (for example a duplicate value).");
    }

    /** MySQL CHECK violations (error 3819) are not classified by Spring as integrity violations. */
    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<ProblemDetail> handleUncategorizedSql(UncategorizedSQLException ex) {
        if (ex.getSQLException() != null && ex.getSQLException().getErrorCode() == 3819) {
            log.warn("Check constraint violated: {}", ex.getSQLException().getMessage());
            return respond(HttpStatus.CONFLICT, "DATA_INTEGRITY", "The change violates a data integrity rule.");
        }
        return handleUnexpected(ex);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported for this endpoint.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Quote the correlation id when reporting it.");
    }
}
