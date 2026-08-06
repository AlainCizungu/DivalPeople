package ai.dival.dip.common.error;

/**
 * The caller is authenticated but must not perform this action.
 *
 * <p>Distinct from Spring Security's {@code AccessDeniedException}, which covers declarative role
 * checks. This one is for refusals the domain decides — a token whose tenant disagrees with the
 * stored record, for instance. The message is logged, never returned.
 */
public class AccessRefusedException extends DomainException {

    public AccessRefusedException(String message) {
        super(message);
    }
}
