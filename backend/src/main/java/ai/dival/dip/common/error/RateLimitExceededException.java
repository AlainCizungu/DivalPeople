package ai.dival.dip.common.error;

/**
 * The caller has made too many requests and must wait.
 *
 * <p>Distinct from {@link AccessRefusedException}: the caller is entitled to do this, just not
 * this often. The distinction matters at the wire, where 429 tells a well-behaved client to back
 * off and 403 tells it to give up.
 */
public class RateLimitExceededException extends DomainException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
