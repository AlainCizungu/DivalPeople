package ai.dival.dip.modules.tix;

/** Kinds of identifying attribute that may be attached to a subject. */
public enum IdentifierType {

    /** Mobile number in E.164 form. */
    MSISDN,
    NATIONAL_ID,
    PASSPORT,
    DRIVER_LICENSE,
    VOTER_CARD,
    /** Business register number, for corporate subjects. */
    RCCM,
    TAX_NUMBER;

    /** Strong identifiers support a deterministic match on their own. */
    public boolean isStrong() {
        return this != MSISDN;
    }
}
