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
    ACCOUNT_REFERENCE,

    /**
     * The name an operator files a customer under, when the delivery carries nothing else.
     *
     * <p>The weakest identity in the system, and the only one nobody issued. It exists because the
     * Orange export has 342 rows and no identifier of any kind — its first column is a row number
     * and its second is the customer name — and a file the platform cannot describe is a file the
     * platform cannot help with.
     *
     * <p><strong>Nobody chooses this on a form.</strong> It is not among the types a mapping may
     * name, and it comes into being only as the consequence of a mapping that declares identity
     * by name. One way in, so there is one place to look when asking how a subject came to be
     * identified this way.
     *
     * <p>Operator-scoped for the same reason as an account reference and a sharper one: two
     * companies in different operators' books may carry the same registered name, and nothing in
     * either file says whether they are one company or two. Not strong, so it never carries an
     * automatic match on the read path.
     *
     * <p>The limit, stated rather than solved: two <em>different</em> companies in one operator's
     * book under one name resolve to one subject. A delivery containing both is refused whole, so
     * it cannot happen inside a single file. Across two deliveries months apart, nothing catches
     * it.
     */
    REPORTED_NAME;

    /**
     * Strong identifiers support a deterministic match on their own.
     *
     * <p>A phone number is reassigned; a name was never issued to anybody. Neither is enough on
     * its own to say two records are about the same person.
     */
    public boolean isStrong() {
        return this != MSISDN && this != REPORTED_NAME;
    }

    /**
     * Whether this identifier means anything outside the operator holding it.
     *
     * <p>The question the whole scoping rule turns on. A national document is issued by an
     * authority and is the same document whoever presents it; an account number is issued by the
     * operator and collides with every other operator's numbering by construction.
     */
    public boolean isOperatorScoped() {
        return this == ACCOUNT_REFERENCE || this == REPORTED_NAME;
    }
}
