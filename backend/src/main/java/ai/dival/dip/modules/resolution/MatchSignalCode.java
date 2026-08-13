package ai.dival.dip.modules.resolution;

/**
 * The things that can be said for or against two records being one subject.
 *
 * <p>The DRC has no single identifier that covers everybody. There is no universal national number
 * that a bank, a telecom operator and a utility all hold for the same person, and the two real
 * operator exports prove it: one identifies its customers by its own account numbers, the other by
 * name alone. Treating that as a defect to be apologised for would leave the platform unable to
 * answer the only question anybody wants answered. Treating it as the problem the platform exists
 * to solve is what this enum is.
 *
 * <p>So: no single signal decides. Several weak agreements, each stated and weighted, produce a
 * confidence, and a person makes the call. That is slower than an identifier lookup and it is the
 * only honest way to do it where the identifier does not exist.
 *
 * <p><strong>The weights are published and add up in public.</strong> Same reasoning as the risk
 * indicator: a confidence a reviewer cannot argue with is a confidence a reviewer cannot check.
 */
public enum MatchSignalCode {

    /**
     * The normalised names are identical.
     *
     * <p>Worth a great deal for a company and much less for a person, which the scorer knows
     * about — "Grand Horizon SARL" is a registered, collision-checked trading name, and
     * "Jean-Pierre Kabamba" is not.
     */
    EXACT_NAME,

    /**
     * The names differ by punctuation, spacing or a token or two.
     *
     * <p>The case this whole feature exists for. "Jean-Pierre Kabamba" and "Jean Pierre Kabamba"
     * are one man and two rows, and no identifier in either file says so.
     */
    SIMILAR_NAME,

    /**
     * Both carry the same national identifier — an RCCM, a passport, a tax number.
     *
     * <p>The strongest thing available, and it is worth being clear about why it can happen at
     * all: national identifiers are globally unique in this registry, so two subjects sharing one
     * is not a coincidence. It is a duplicate that got in before the identifier did.
     *
     * <p><strong>One line, read both ways.</strong> Agreement here is the strongest evidence for;
     * disagreement is the strongest evidence against, and heavier, because two companies holding
     * two different RCCM numbers are two companies whatever their names look like. An earlier
     * draft had a second code for the conflict, which put one attribute on two rows and made a
     * reviewer read both to learn one thing.
     */
    SHARED_NATIONAL_IDENTIFIER,

    /** Both are businesses, or both are people. */
    SAME_SUBJECT_TYPE,

    /** Same declared nationality or country of registration. */
    SAME_NATIONALITY,

    /**
     * Date of birth, which is what actually resolves a person when it is there.
     *
     * <p>Like the identifier above, this line reads both ways. Two dates that agree are close to
     * decisive alongside a name; two that disagree end the discussion, because namesakes exist in
     * quantity and a father and a son are not one person.
     */
    SAME_DATE_OF_BIRTH,

    /**
     * Each carries an account reference and they are different numbers.
     *
     * <p>Worth nothing either way, and shown anyway because a reviewer will notice it and wonder.
     * Two operators number their own customers from one upwards, so two records for one person
     * <em>necessarily</em> carry different account references. Reading that as evidence against
     * would refuse every genuine cross-operator match there is.
     */
    DIFFERENT_ACCOUNT_REFERENCES,

    /**
     * A second contact number held by both. <strong>Never available.</strong>
     *
     * <p>Neither operator export carries one. Listed rather than omitted because this is the
     * concrete ask: a delivery with a secondary number attached would move a reviewer's confidence
     * further than any refinement of the name comparison could, and the screen showing that gap is
     * how the ask gets made.
     */
    SAME_SECONDARY_PHONE,

    /** City or commune held by both. <strong>Never available</strong> — no delivery carries one. */
    SAME_CITY,

    /** Street address held by both. <strong>Never available</strong> — no delivery carries one. */
    SIMILAR_ADDRESS
}
