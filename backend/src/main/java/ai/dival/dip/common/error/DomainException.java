package ai.dival.dip.common.error;

/**
 * Base for errors that describe a business outcome rather than a bug.
 *
 * <p>Modules throw these so the web layer can translate them without knowing what a debt record
 * or a tenant is. That keeps the dependency pointing one way — modules depend on common, never
 * the reverse — which is checked in CI by {@code scripts/check_architecture.py}.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
