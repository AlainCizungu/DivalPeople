package ai.dival.dip.modules.resolution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Compares two records and says how confident it is that they are one subject.
 *
 * <p>Pure — no clock, no repository, no entity — for the same reason the risk model is: this is
 * the part somebody will be asked to justify, so it has to be readable on its own and testable
 * without a database.
 *
 * <p><strong>It never decides.</strong> There is no confidence at which this class merges
 * anything. Merging moves one company's defaults onto another company's file, across institutions
 * that cannot see each other, and the cost of being wrong is somebody refused credit for a debt
 * that is not theirs. A high number means a reviewer will probably agree with it.
 *
 * <p>Three of the twelve signals can never be evaluated today, and they are returned as
 * unavailable rather than left out. That is the honest report and it is also the product argument:
 * a delivery carrying a city and a second phone number would move a reviewer further than any
 * amount of cleverness about spelling, and the only way that ask gets made is if the gap is
 * visible on the screen every time.
 */
@Component
public class MatchScorer {

    // --- weights ------------------------------------------------------------
    //
    // Published, and they add up in public. The name weights differ by an order of magnitude
    // between a company and a person, and that is the single most important number in the file: a
    // registered trading name is chosen to be distinctive and checked for collision, and
    // "Jean-Pierre Kabamba" is neither. The profiled Vodacom export had 48 names on more than one
    // account inside a single operator's book.

    private static final double BUSINESS_EXACT_NAME = 0.55;
    private static final double BUSINESS_SIMILAR_NAME = 0.40;

    /**
     * Lower than a business name, and not as much lower as the first draft had it.
     *
     * <p>Two bars are being set here and it took getting them confused to see the difference.
     * <em>Worth a reviewer's glance</em> is not <em>enough to merge</em>, and the person weights
     * were originally pitched at the second — which put "Jean-Pierre Kabamba" against "Jean Pierre
     * Kabamba" at 0.31, below the threshold, so the queue would never have shown a reviewer the
     * one case this feature exists for.
     *
     * <p>The bar for merging is held elsewhere and held absolutely: no confidence merges anything
     * without a person pressing a button. So these can be set to what makes a queue useful. A
     * matching full name for an individual is roughly a one-in-a-hundred coincidence in the
     * profiled export — worth looking at, nowhere near enough on its own.
     */
    private static final double PERSON_EXACT_NAME = 0.40;
    private static final double PERSON_SIMILAR_NAME = 0.32;

    private static final double SHARED_NATIONAL_IDENTIFIER = 0.50;
    private static final double SAME_SUBJECT_TYPE = 0.05;
    private static final double SAME_NATIONALITY = 0.05;
    private static final double SAME_DATE_OF_BIRTH = 0.25;

    /**
     * Heavy, and heavier than any single agreement.
     *
     * <p>Two companies holding two different RCCM numbers are two companies, whatever their names
     * look like. If a conflict weighed less than an exact name match, a pair of genuinely distinct
     * businesses trading under one name would sit in the queue at high confidence for ever.
     */
    private static final double CONFLICTING_NATIONAL_IDENTIFIER = -0.60;
    private static final double CONFLICTING_DATE_OF_BIRTH = -0.60;

    /** How alike two names have to be before "similar" is claimed at all. */
    private static final double SIMILAR_NAME_THRESHOLD = 0.82;

    public MatchAssessment compare(SubjectFacts left, SubjectFacts right) {
        List<MatchSignal> signals = new ArrayList<>();

        boolean business = left.business() && right.business();
        signals.add(nameSignal(left, right, business));

        // Only types both records carry. One side holding an RCCM and the other holding none is a
        // gap, not a disagreement, and the whole country is full of gaps.
        Set<String> shared = left.identifierTypesSharedWith(right);
        boolean anyIdentifierConflicts = shared.stream().anyMatch(type ->
                !left.nationalIdentifiers().get(type).equals(right.nationalIdentifiers().get(type)));

        // Conflict wins where both are true — same tax number, different RCCM. That pair is not a
        // near-match with a caveat, it is a contradiction, and the number a reviewer sees should
        // say so rather than averaging the two into something reassuring.
        signals.add(shared.isEmpty()
                ? MatchSignal.unavailable(MatchSignalCode.SHARED_NATIONAL_IDENTIFIER)
                : anyIdentifierConflicts
                        ? MatchSignal.conflicts(MatchSignalCode.SHARED_NATIONAL_IDENTIFIER,
                                CONFLICTING_NATIONAL_IDENTIFIER)
                        : MatchSignal.agrees(MatchSignalCode.SHARED_NATIONAL_IDENTIFIER,
                                SHARED_NATIONAL_IDENTIFIER));

        signals.add(left.business() == right.business()
                ? MatchSignal.agrees(MatchSignalCode.SAME_SUBJECT_TYPE, SAME_SUBJECT_TYPE)
                : MatchSignal.conflicts(MatchSignalCode.SAME_SUBJECT_TYPE, 0.0));

        signals.add(bothPresent(left.nationality(), right.nationality())
                ? (Objects.equals(left.nationality(), right.nationality())
                        ? MatchSignal.agrees(MatchSignalCode.SAME_NATIONALITY, SAME_NATIONALITY)
                        : MatchSignal.conflicts(MatchSignalCode.SAME_NATIONALITY, 0.0))
                : MatchSignal.unavailable(MatchSignalCode.SAME_NATIONALITY));

        boolean bothBorn = left.dateOfBirth() != null && right.dateOfBirth() != null;
        signals.add(!bothBorn
                ? MatchSignal.unavailable(MatchSignalCode.SAME_DATE_OF_BIRTH)
                : left.dateOfBirth().equals(right.dateOfBirth())
                        ? MatchSignal.agrees(MatchSignalCode.SAME_DATE_OF_BIRTH, SAME_DATE_OF_BIRTH)
                        : MatchSignal.conflicts(MatchSignalCode.SAME_DATE_OF_BIRTH,
                                CONFLICTING_DATE_OF_BIRTH));

        // Different account numbers at two institutions are the normal state of a genuine match,
        // not a mark against it. Shown because a reviewer will notice and wonder, weighted at
        // nothing because reading it as evidence would refuse every cross-operator match there is.
        signals.add(left.hasAccountReference() && right.hasAccountReference()
                ? MatchSignal.neutral(MatchSignalCode.DIFFERENT_ACCOUNT_REFERENCES)
                : MatchSignal.unavailable(MatchSignalCode.DIFFERENT_ACCOUNT_REFERENCES));

        // The three the platform cannot answer at all, and the reason this list is worth reading
        // even when it decides nothing.
        signals.add(MatchSignal.unavailable(MatchSignalCode.SAME_SECONDARY_PHONE));
        signals.add(MatchSignal.unavailable(MatchSignalCode.SAME_CITY));
        signals.add(MatchSignal.unavailable(MatchSignalCode.SIMILAR_ADDRESS));

        double confidence = signals.stream().mapToDouble(MatchSignal::weight).sum();
        return new MatchAssessment(clamp(confidence), signals);
    }

    private MatchSignal nameSignal(SubjectFacts left, SubjectFacts right, boolean business) {
        // Exact after the punctuation is flattened, not before. "Jean-Pierre Kabamba" and "Jean
        // Pierre Kabamba" are the same name written twice, and grading one of them merely similar
        // would make the confidence depend on which clerk typed which record.
        if (flattenPunctuation(left.normalizedName())
                .equals(flattenPunctuation(right.normalizedName()))) {
            return MatchSignal.agrees(MatchSignalCode.EXACT_NAME,
                    business ? BUSINESS_EXACT_NAME : PERSON_EXACT_NAME);
        }
        double similarity = similarity(left.normalizedName(), right.normalizedName());
        if (similarity >= SIMILAR_NAME_THRESHOLD) {
            // Scaled by how alike they actually are, so "Jean Pierre" against "Jean-Pierre" is
            // worth more than two names that scrape over the line.
            return MatchSignal.agrees(MatchSignalCode.SIMILAR_NAME,
                    (business ? BUSINESS_SIMILAR_NAME : PERSON_SIMILAR_NAME) * similarity);
        }
        return MatchSignal.conflicts(MatchSignalCode.SIMILAR_NAME, 0.0);
    }

    /**
     * How alike two names are, between 0 and 1.
     *
     * <p>Two comparisons, and the larger wins. Edit distance catches a hyphen, a doubled letter
     * or a transliteration — "Jean-Pierre" against "Jean Pierre". Token overlap catches a dropped
     * or added word — "DMARK DRC SARL" against "DMARK DRC" — which edit distance scores badly
     * because it counts five deleted characters against a short string.
     *
     * <p>Not a library. Both are twenty lines, both are explainable to somebody disputing a merge,
     * and neither needs a dependency that would have to be justified to a bank's review.
     */
    private double similarity(String left, String right) {
        String a = flattenPunctuation(left);
        String b = flattenPunctuation(right);
        return Math.max(editSimilarity(a, b), tokenOverlap(a, b));
    }

    /**
     * Hyphens and apostrophes become spaces before anything is compared.
     *
     * <p>{@code Subject.normalizeName} strips accents and case and leaves punctuation alone,
     * correctly — it is a storage key and has to be predictable. Here the question is different:
     * "Jean-Pierre" and "Jean Pierre" are one name written two ways, and a comparison that counts
     * the hyphen as a difference is measuring a typing convention rather than an identity.
     *
     * <p>It matters most to the token comparison, where the hyphen is the difference between two
     * tokens and three, and so between an overlap of a half and an overlap of one.
     */
    private String flattenPunctuation(String name) {
        return name.replaceAll("[-'’.]", " ").replaceAll("\\s+", " ").trim();
    }

    private double editSimilarity(String left, String right) {
        int longest = Math.max(left.length(), right.length());
        if (longest == 0) {
            return 1.0;
        }
        return 1.0 - ((double) editDistance(left, right) / longest);
    }

    /** Levenshtein, two rows at a time rather than a full matrix. */
    private int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution,
                        Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * The proportion of the shorter name's words that appear in the longer one.
     *
     * <p>Against the shorter rather than against the union, deliberately. "DMARK DRC" is entirely
     * contained in "DMARK DRC SARL", and a legal suffix somebody typed once and omitted the next
     * time should not halve the score.
     */
    private double tokenOverlap(String left, String right) {
        Set<String> leftTokens = new LinkedHashSet<>(Arrays.asList(left.split("\\s+")));
        Set<String> rightTokens = new LinkedHashSet<>(Arrays.asList(right.split("\\s+")));
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> smaller = leftTokens.size() <= rightTokens.size() ? leftTokens : rightTokens;
        Set<String> larger = smaller == leftTokens ? rightTokens : leftTokens;
        long shared = smaller.stream().filter(larger::contains).count();
        return (double) shared / smaller.size();
    }

    private static boolean bothPresent(String left, String right) {
        return left != null && !left.isBlank() && right != null && !right.isBlank();
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
