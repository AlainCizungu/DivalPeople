package ai.dival.dip.modules.analyst;

import java.math.BigDecimal;

/**
 * What the analyst understood, shown to the user before the answer.
 *
 * <p>Printed on screen in those words — "understood as: companies owing more than 20,000 USD" —
 * because the failure mode of a natural-language front end is not a wrong number, it is a right
 * number to a different question. A reader who can see the interpretation can catch that in a
 * second; one who cannot has to reverse-engineer it from the result.
 *
 * @param intent      which of the closed set of questions this was taken to be
 * @param minAmount   the threshold, where the intent has one
 * @param days        the window, where the intent has one
 * @param subjectName the company named in the question, for the single-company intents
 * @param byModel     whether a language model read the question or the rules did. Stamped so an
 *                    answer can be traced to how it was understood, and so a run of odd
 *                    interpretations can be attributed to the right thing
 */
public record Interpretation(Intent intent, BigDecimal minAmount, int days, String subjectName,
                             boolean byModel) {

    public static Interpretation unsupported(boolean byModel) {
        return new Interpretation(Intent.UNSUPPORTED, null, 0, null, byModel);
    }

    public Interpretation asRuleBased() {
        return new Interpretation(intent, minAmount, days, subjectName, false);
    }
}
