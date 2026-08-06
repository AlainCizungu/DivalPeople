package ai.dival.dip.common.error;

/**
 * The request is well formed but collides with existing state — a name already taken, a record
 * already settled.
 *
 * <p>The message is returned to the caller, because a conflict is theirs to resolve and they
 * cannot resolve it without knowing what it was. Never put anything sensitive in one.
 */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
