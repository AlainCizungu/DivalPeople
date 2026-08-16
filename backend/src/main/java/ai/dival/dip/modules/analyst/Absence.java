package ai.dival.dip.modules.analyst;

/**
 * A thing the pack does not contain, and the reason.
 *
 * <p>Coded rather than phrased, so the screen can say each one in either language and so a reader
 * can tell a permanent design decision from a gap in this particular company's file. Four of these
 * appear on every pack; two appear only when they apply.
 *
 * <p>They are here at all because of what this pack is for. It is the grounding a generated summary
 * would rest on, and a summary that silently omits what it could not find reads as complete. The
 * absences are evidence.
 */
public enum Absence {

    /**
     * No language model wrote any of this.
     *
     * <p>First in the list and stated on every pack, because the screen is reached from a menu
     * entry called "Dival AI analyst" and a reader is entitled to know that nothing here was
     * generated. None is configured in this deployment; the boundary was built before the model
     * rather than after, since a model handed a database connection would answer questions the
     * exchange spends its whole design refusing.
     */
    NO_MODEL_PRODUCED_THIS,

    /**
     * How many institutions report, never which.
     *
     * <p>The exchange's central promise. A count is a fact about the company; a name is a fact
     * about a competitor's customer relationship.
     */
    OTHER_OPERATORS_ARE_NOT_NAMED,

    /**
     * What another institution is owed is not here.
     *
     * <p>Your own amounts are, in full — they are yours. An amount from elsewhere would tell you
     * the size of a rival's commercial relationship, which is not a fact about the debtor.
     */
    OTHER_OPERATORS_AMOUNTS_ARE_NOT_DISCLOSED,

    /**
     * A contested record is withheld from the moment it is contested, at every operator.
     *
     * <p>Not only the record of the institution the dispute was raised against — <em>every</em>
     * record about that company leaves the exchange, including yours. Before anybody decides who
     * is right, because the harm of being wrongly listed accrues daily.
     *
     * <p>So a pack about a contested company does not merely go quieter: the exchange can answer
     * CLEAR with nought institutions while the registry still holds live debts. That is the trade
     * the design makes, and stating it is the difference between a reader understanding the answer
     * and misreading silence as absence of debt. Your own file is unaffected and appears in full;
     * suppression governs what the exchange tells others, not what you may see of your own.
     */
    CONTESTED_RECORDS_ARE_WITHHELD,

    /**
     * This company has no national document on file.
     *
     * <p>An RCCM, a tax number, a passport — something that identifies it to anybody rather than
     * only to you. Without one the exchange is asked by name, which is rarely enough, and this is
     * the concrete thing to go and collect.
     */
    NO_NATIONAL_DOCUMENT_IS_HELD,

    /**
     * The exchange declined to confirm this is the same company.
     *
     * <p>Not a finding about the company. The match fell below the threshold at which the platform
     * will answer without a person looking, so anything it might have said is withheld rather than
     * guessed. Usually the same story as the entry above.
     */
    THE_EXCHANGE_WOULD_NOT_CONFIRM_IDENTITY
}
