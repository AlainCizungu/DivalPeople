package ai.dival.dip.modules.tix;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of another operator's book this exchange is allowed to show.
 *
 * <p><strong>Both switches ship off, and off is not a placeholder.</strong> Every operator that has
 * been shown this platform was shown a registry that answers with a count — how many institutions
 * report a subject, never which and never how much. That is not a rendering choice; it is the
 * sentence that makes a competitor willing to put its receivables ledger into a shared database.
 * Turning these on changes what participants are agreeing to, so it is a deployment decision
 * somebody signs for, not a default somebody inherits.
 *
 * <p>The product owner has asked for the named view and it is built. It is built <em>off</em>
 * because two things have to happen before it may be switched on, and neither of them is
 * engineering:
 *
 * <ul>
 *   <li>Counsel has to answer. The question — may the amount owed be disclosed to other
 *       institutions — is already open in {@code docs/OLIVIER_ANSWERS.md}; naming the creditor
 *       alongside it is strictly more than was asked about.
 *   <li>Participants have to be told. An operator that joined on "never which, never how much" and
 *       finds its ledger itemised on a competitor's screen has not been given a new feature, it
 *       has been given a reason to leave and a reason to sue.
 * </ul>
 *
 * <p><strong>Two switches rather than one</strong>, because they disclose different things and a
 * deployment may reasonably want one without the other. Naming the institutions tells a lender who
 * else is in the room, which is roughly what a syndication check needs. Disclosing the amounts
 * tells them the size of every position, which is a competitor's revenue by customer. Naming with
 * amounts withheld is a coherent middle setting; amounts with names withheld is not, so
 * {@code discloseAmounts} does nothing on its own — see {@link #canName()} and
 * {@link #canPrice()}.
 *
 * <p>Nothing here is secret and nothing here is a key, so unlike the AI settings these are plain
 * booleans with no credential attached. What they gate is not access to a system; it is a promise.
 *
 * @param nameInstitutions name the operators that report a subject, instead of counting them
 * @param discloseAmounts  show what each named operator is owed. Read only when
 *                         {@code nameInstitutions} is on, because an itemised list of anonymous
 *                         amounts is the same disclosure with an extra step
 */
@ConfigurationProperties(prefix = "dip.disclosure")
public record DisclosureProperties(boolean nameInstitutions, boolean discloseAmounts) {

    /**
     * The shape a deployment that configured nothing gets.
     *
     * <p>Spring binds a missing property to {@code false} already. This exists so that a test, or
     * any code constructing the record directly, cannot get the permissive shape by accident — the
     * safe value is the one you get for free, and the unsafe one has to be typed out.
     */
    public static DisclosureProperties countOnly() {
        return new DisclosureProperties(false, false);
    }

    /** Whether the operators reporting a subject may be named rather than counted. */
    public boolean canName() {
        return nameInstitutions;
    }

    /**
     * Whether a named operator's total may be shown.
     *
     * <p>Requires naming to be on. An amount attached to nobody is not a safer disclosure — it is
     * the same list with the labels a screen away, and a reader with two inquiries and a
     * subtraction recovers them.
     */
    public boolean canPrice() {
        return nameInstitutions && discloseAmounts;
    }
}
