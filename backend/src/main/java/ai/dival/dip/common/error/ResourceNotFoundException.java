package ai.dival.dip.common.error;

/**
 * The requested resource does not exist, or does not exist for this caller.
 *
 * <p>Those two cases are deliberately indistinguishable from the outside. Telling a caller that a
 * record exists but belongs to someone else turns a 404 into an enumeration oracle.
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
