package ai.dival.dip.modules.recruitment;

/** Lifecycle of an offer. */
public enum OfferStatus {

    DRAFT,
    SENT,
    ACCEPTED,
    DECLINED,

    /** Pulled by the employer before an answer. */
    WITHDRAWN,

    /** Its deadline passed without a response. */
    EXPIRED;

    public boolean isOpen() {
        return this == DRAFT || this == SENT;
    }
}
