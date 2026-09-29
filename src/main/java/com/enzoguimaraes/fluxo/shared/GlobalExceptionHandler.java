package com.enzoguimaraes.fluxo.shared;

import com.enzoguimaraes.fluxo.client.DuplicateClientEmailException;
import com.enzoguimaraes.fluxo.client.ClientNotFoundException;
import com.enzoguimaraes.fluxo.charge.ChargeNotFoundException;
import com.enzoguimaraes.fluxo.charge.InvalidChargeDueDateException;
import com.enzoguimaraes.fluxo.charge.InvalidChargeTransitionException;
import com.enzoguimaraes.fluxo.payment.IdempotencyKeyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleBodyValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        var fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_ERROR", request, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleParameterValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        var fieldErrors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldValidationError(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage()
                        )))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_ERROR", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        var fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldValidationError(
                        lastPathSegment(violation.getPropertyPath().toString()),
                        violation.getMessage()
                ))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_ERROR", request, fieldErrors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Request could not be read",
                "INVALID_REQUEST",
                request,
                List.of()
        );
    }

    @ExceptionHandler(DuplicateClientEmailException.class)
    ResponseEntity<ProblemDetail> handleDuplicateEmail(
            DuplicateClientEmailException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                "CLIENT_EMAIL_ALREADY_EXISTS",
                request,
                null
        );
    }

    @ExceptionHandler(ClientNotFoundException.class)
    ResponseEntity<ProblemDetail> handleClientNotFound(
            ClientNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), "CLIENT_NOT_FOUND", request, null);
    }

    @ExceptionHandler(ChargeNotFoundException.class)
    ResponseEntity<ProblemDetail> handleChargeNotFound(
            ChargeNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), "CHARGE_NOT_FOUND", request, null);
    }

    @ExceptionHandler(InvalidChargeDueDateException.class)
    ResponseEntity<ProblemDetail> handleInvalidDueDate(
            InvalidChargeDueDateException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "VALIDATION_ERROR",
                request,
                List.of(new FieldValidationError("dueDate", exception.getMessage()))
        );
    }

    @ExceptionHandler(InvalidChargeTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidTransition(
            InvalidChargeTransitionException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                "INVALID_CHARGE_TRANSITION",
                request,
                null
        );
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                "IDEMPOTENCY_KEY_CONFLICT",
                request,
                null
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected request failure traceId={}", traceId(), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                "INTERNAL_ERROR",
                request,
                null
        );
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String detail,
            String code,
            HttpServletRequest request,
            List<FieldValidationError> fieldErrors
    ) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId());
        if (fieldErrors != null) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status).body(problem);
    }

    private FieldValidationError toFieldError(FieldError error) {
        return new FieldValidationError(error.getField(), error.getDefaultMessage());
    }

    private String lastPathSegment(String path) {
        var separator = path.lastIndexOf('.');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    private String traceId() {
        var traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        return traceId == null ? "unavailable" : traceId;
    }
}
