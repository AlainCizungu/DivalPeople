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
    TAX_NUMBER,

    /**
     * An operator's own customer or account number.
     *
     * <p>Added because the real telecom exports have nothing else. Every row of the Vodacom
     * write-off file is identified by a value like {@code V0172109}, which is not a document any
     * registry issued — it is a number Vodacom assigned. Without this type the only way to import
     * that file was to declare those numbers to be RCCM registrations, putting a false statement
     * about a company into a registry other operators read as fact.
     *
     * <p><strong>It identifies a subject inside one operator and nowhere else.</strong> Operators
     * number their customers from one upwards, so account 100234 exists at every one of them and
     * refers to a different company at each. That is why this type carries the issuing operator in
     * its identity — see {@code V26__account_reference.sql} — and why it can never resolve a
     * subject for anybody but the operator that recorded it.
     *
     * <p>Worth stating plainly, because it is the uncomfortable consequence: records imported with
     * only an account reference are visible to their own operator and invisible to the exchange.
     * Another operator asking about the same company finds nothing, and is right to, because
     * nothing in the file says who that company is in any national register. The fix is not
     * technical. It is for the operator to supply an RCCM or a tax number alongside their account
     * number, and the honest thing is for the product to show that gap rather than paper over it
     * with a match nobody can justify.
     */
    ACCOUNT_REFERENCE;

    /** Strong identifiers support a deterministic match on their own. */
    public boolean isStrong() {
        return this != MSISDN;
    }

    /**
     * Whether this identifier means anything outside the operator holding it.
     *
     * <p>The question the whole scoping rule turns on. A national document is issued by an
     * authority and is the same document whoever presents it; an account number is issued by the
     * operator and collides with every other operator's numbering by construction.
     */
    public boolean isOperatorScoped() {
        return this == ACCOUNT_REFERENCE;
    }
}
