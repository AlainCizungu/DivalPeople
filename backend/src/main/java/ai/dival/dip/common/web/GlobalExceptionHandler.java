package ai.dival.dip.common.web;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tix.DebtRecordService;
import ai.dival.dip.modules.users.CurrentUserService;
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
 * <p>Messages are intentionally generic. An error response must not reveal whether a record
 * exists under a different tenant, which would turn a 404 into an enumeration oracle.
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

    @ExceptionHandler(DebtRecordService.DebtRecordNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(DebtRecordService.DebtRecordNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "The requested resource was not found"));
    }

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
     * A token whose tenant claim disagrees with the stored record for that identity. Treated as
     * forbidden and logged, because it should be impossible in normal operation.
     */
    @ExceptionHandler(CurrentUserService.TenantMismatchException.class)
    public ResponseEntity<ApiError> handleTenantMismatch(CurrentUserService.TenantMismatchException ex) {
        log.warn("Rejected a request whose token tenant disagrees with the stored user record");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "You do not have permission to perform this action"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", ex.getMessage()));
    }
}
