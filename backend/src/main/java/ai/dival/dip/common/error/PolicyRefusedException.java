package ai.dival.dip.common.error;

/**
 * The request is well formed, the caller is entitled to make it, and the rules say no.
 *
 * <p>Distinct from {@link ConflictException}, which is about colliding with existing state, and
 * from {@link AccessRefusedException}, which is about who is asking. This one is about what the
 * system is permitted to do at all: a debt below the reporting threshold, a declaration in a
 * currency nobody has set a floor for, a record whose retention period has run out.
 *
 * <p>The message is returned to the caller. That is safe here and necessary: a refusal on policy
 * grounds is only useful if the operator can tell which rule refused it and change the request.
 * A silent or generic "no" produces a support ticket instead of a corrected submission. Nothing
 * about another party's data belongs in one of these messages.
 */
public class PolicyRefusedException extends DomainException {

    public PolicyRefusedException(String message) {
        super(message);
    }
}
