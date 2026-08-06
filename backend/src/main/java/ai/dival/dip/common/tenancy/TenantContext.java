package ai.dival.dip.common.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the tenant for the current thread of execution.
 *
 * <p>The tenant is derived from the authenticated principal by {@link TenantResolutionFilter}
 * and is never read from a request header, query parameter, or body field. Anything that
 * accepts a tenant identifier from the client is a cross-tenant vulnerability.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> find() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * @throws TenantContextMissingException when no tenant is bound, which means a tenant-scoped
     *                                       operation was reached outside an authenticated request.
     */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new TenantContextMissingException();
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs an action bound to a specific tenant, restoring the previous binding afterwards. */
    public static void runAs(UUID tenantId, Runnable action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** As {@link #runAs}, for actions that produce a value. */
    public static <T> T runAsResult(UUID tenantId, java.util.function.Supplier<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static class TenantContextMissingException extends IllegalStateException {
        public TenantContextMissingException() {
            super("No tenant bound to the current context");
        }
    }
}
