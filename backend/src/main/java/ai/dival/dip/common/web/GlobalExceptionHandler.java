package ai.dival.dip.common.web;

import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.RateLimitExceededException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into a stable error shape.
 *
 * <p>Handles the abstractions in {@code common.error} rather than the concrete exceptions modules
 * throw, so the web layer needs to know nothing about debt records or tenants. That keeps the
 * dependency pointing one way and is enforced by {@code scripts/check_architecture.py}.
 *
 * <p>Messages are deliberately generic except for conflicts. An error response must not reveal
 * whether a record exists under a different tenant, which would turn a 404 into an enumeration
 * oracle.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String code, String message, Instant timestamp) {
        static ApiError of(String code, String message) {
            return new ApiError(code, message, Instant.now());
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "The request payload is not valid"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "The requested resource was not found"));
    }

    /** A conflict is the caller's to resolve, so the reason is safe and useful to return. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONFLICT", ex.getMessage()));
    }

    /** A refusal decided by the domain. Logged, because it should not happen in normal operation. */
    @ExceptionHandler(AccessRefusedException.class)
    public ResponseEntity<ApiError> handleAccessRefused(AccessRefusedException ex) {
        log.warn("Refused a request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "You do not have permission to perform this action"));
    }

    /** A refusal decided by Spring Security's declarative role checks. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "You do not have permission to perform this action"));
    }

    @ExceptionHandler(TenantContext.TenantContextMissingException.class)
    public ResponseEntity<ApiError> handleMissingTenant(TenantContext.TenantContextMissingException ex) {
        log.warn("Tenant-scoped operation reached without a bound tenant");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("TENANT_REQUIRED", "Authentication is required"));
    }

    /**
     * Carries {@code Retry-After}, because a limit without one tells a client to guess, and
     * clients guess badly — usually by retrying immediately and making the problem worse.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiError.of("RATE_LIMITED", ex.getMessage()));
    }

    /**
     * 422 rather than 400, deliberately. The payload was understood and is syntactically fine —
     * a client that retries it unchanged will be refused again, and the distinction from a
     * malformed request is what tells an integrator to fix the submission rather than the parser.
     */
    @ExceptionHandler(PolicyRefusedException.class)
    public ResponseEntity<ApiError> handlePolicyRefused(PolicyRefusedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("POLICY_REFUSED", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", ex.getMessage()));
    }
}
