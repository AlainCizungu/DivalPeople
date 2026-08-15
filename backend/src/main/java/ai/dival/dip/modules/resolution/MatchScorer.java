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
 * <p>Eleven signals are reported on every comparison, from twelve codes — the two name codes are
 * mutually exclusive, so exactly one of them appears. That invariant is held by
 * {@code theListIsAlwaysTheSame} rather than by this sentence, which is the third count in this
 * file's history to have been written down wrong. A signal with nothing to compare is returned as unavailable rather than
 * left out, and that reporting is what produced two of the twelve: sector and address read
 * <em>never available</em> on every case this screen ever showed, counsel independently named both
 * as matching criteria, and the gap being visible every time is how the ask got made. One is left —
 * a second contact number, which no delivery carries.
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
     * <p>Two taxpayers holding two different tax numbers are two taxpayers, whatever their names
     * look like. If a conflict weighed less than an exact name match, a pair of genuinely distinct
     * businesses trading under one name would sit in the queue at high confidence for ever.
     *
     * <p>The register number used to be weighed here and no longer is — see below.
     */
    private static final double CONFLICTING_NATIONAL_IDENTIFIER = -0.60;
    private static final double CONFLICTING_DATE_OF_BIRTH = -0.60;

    /**
     * The register number, agreeing and disagreeing, and the two are not mirror images.
     *
     * <p>Agreement is worth what any national identifier is worth: the same RCCM on two records is
     * one company. <strong>Disagreement is worth a fifth of that</strong>, because counsel's answer
     * of August 2026 is that an RCCM is reissued when a company amends its statutes or adds
     * capital. A different number is therefore weak evidence of a different company and strong
     * evidence of nothing.
     *
     * <p>The arithmetic this changes, on the case it was written for: two records, one exact
     * business name, two different RCCMs and nothing else. At the old weight that scored
     * 0.55 − 0.60 + 0.05 = zero after clamping, so the pair was invisible to the queue for ever —
     * the rule written to prevent a false merge was reliably hiding the true one. At −0.15 it
     * scores 0.45 and a person looks at it.
     *
     * <p>Two genuinely different companies trading under one name now land in that same queue,
     * and that is the honest position rather than a regression: on a name and a register number
     * alone the platform cannot tell those two cases apart, and pretending otherwise is what it
     * was doing before. A conflicting <em>tax</em> number still ends the discussion — 0.55 − 0.15
     * − 0.60 + 0.05 clamps back to zero — which is why splitting the two mattered.
     */
    private static final double SHARED_REGISTER_NUMBER = 0.50;
    private static final double CONFLICTING_REGISTER_NUMBER = -0.15;

    /**
     * The identifier type that behaves this way, as a string.
     *
     * <p>A string rather than the telecom module's enum, because this module imports nothing from
     * that one and that constraint is what lets the scorer be read on its own. Public so a test on
     * the other side of the boundary can assert the two still agree — a rename over there would
     * otherwise silently turn this rule off and every RCCM would go back to being decisive.
     */
    public static final String REGISTER_NUMBER_TYPE = "RCCM";

    /** How alike two names have to be before "similar" is claimed at all. */
    private static final double SIMILAR_NAME_THRESHOLD = 0.82;

    /**
     * Sector, city and street — the two elements counsel named that the registry did not hold.
     *
     * <p>Each is deliberately shaped differently, because they behave differently.
     *
     * <p><strong>Sector</strong> is light on agreement and heavier on disagreement. A great many
     * Congolese companies are in general trade, so agreeing says little; two companies of the same
     * name in transport and in pharmaceuticals are two companies, so disagreeing says more.
     *
     * <p><strong>City</strong> carries the real negative weight. It is the one address component
     * that compares as an equality, and it is what separates two homonymous companies — the case
     * counsel raised and the case the softened register-number rule now puts in front of a person.
     *
     * <p><strong>Street</strong> is asymmetric to the point of being one-sided: a matching address
     * is close to decisive beside a name, and a differing one is weighed at nothing. Free-text
     * addresses differ between two clerks at least as often as between two companies, and a company
     * that moved keeps its identity. Weighing that difference against a match would refuse genuine
     * pairs on the strength of somebody's punctuation.
     *
     * <p>Compared with the same routine that compares names, not because addresses are names but
     * because the failure mode is identical: an abbreviation, a dropped token, a hyphen.
     */
    private static final double SAME_SECTOR = 0.08;
    private static final double CONFLICTING_SECTOR = -0.15;
    private static final double SAME_CITY = 0.10;
    private static final double CONFLICTING_CITY = -0.25;
    private static final double SIMILAR_ADDRESS = 0.25;

    /**
     * Looser than the name threshold, and set from a measurement rather than by eye.
     *
     * <p>"12 av. Kasa-Vubu" against "12, avenue Kasa Vubu" — one address, two clerks, and the
     * commonest shape this comparison will meet — scores 0.789. A threshold of 0.75 would have sat
     * almost exactly on it, which is not a threshold but a coin toss: a slightly longer street name
     * would have fallen the other side of it for no reason anybody could explain to a reviewer.
     *
     * <p>The cost of being too generous is small and asymmetric. A false "similar" adds at most a
     * quarter, scaled down by how alike the two actually are, and a false "different" costs
     * nothing at all — the signal never counts against a pair.
     *
     * <p>Known limitation, recorded rather than hidden: an abbreviation is still expensive.
     * "av." against "avenue" alone holds that pair to 0.789 rather than 1.0, which costs the
     * assessment about five points. Fixing it means a dictionary of Congolese street abbreviations,
     * which is local knowledge this file should not invent.
     */
    private static final double SIMILAR_ADDRESS_THRESHOLD = 0.70;

    public MatchAssessment compare(SubjectFacts left, SubjectFacts right) {
        List<MatchSignal> signals = new ArrayList<>();

        boolean business = left.business() && right.business();
        signals.add(nameSignal(left, right, business));

        // Only types both records carry. One side holding an RCCM and the other holding none is a
        // gap, not a disagreement, and the whole country is full of gaps.
        Set<String> shared = left.identifierTypesSharedWith(right);

        // The register number on its own row, because it agrees and disagrees with different
        // force. Everything else keeps the old symmetric treatment.
        signals.add(signalFor(left, right, shared.contains(REGISTER_NUMBER_TYPE)
                        ? Set.of(REGISTER_NUMBER_TYPE) : Set.of(),
                MatchSignalCode.SHARED_REGISTER_NUMBER,
                SHARED_REGISTER_NUMBER, CONFLICTING_REGISTER_NUMBER));

        Set<String> documents = new LinkedHashSet<>(shared);
        documents.remove(REGISTER_NUMBER_TYPE);
        signals.add(signalFor(left, right, documents,
                MatchSignalCode.SHARED_NATIONAL_IDENTIFIER,
                SHARED_NATIONAL_IDENTIFIER, CONFLICTING_NATIONAL_IDENTIFIER));

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

        // One left that the platform cannot answer at all. It was three until August 2026; the
        // other two are below, and they are the reason this list was worth reading while it still
        // decided nothing — the gap being visible on every case is how the ask got made.
        signals.add(MatchSignal.unavailable(MatchSignalCode.SAME_SECONDARY_PHONE));

        signals.add(equality(left.sector(), right.sector(), MatchSignalCode.SAME_SECTOR,
                SAME_SECTOR, CONFLICTING_SECTOR));
        signals.add(equality(left.city(), right.city(), MatchSignalCode.SAME_CITY,
                SAME_CITY, CONFLICTING_CITY));
        signals.add(addressSignal(left, right));

        double confidence = signals.stream().mapToDouble(MatchSignal::weight).sum();
        return new MatchAssessment(clamp(confidence), signals);
    }

    /**
     * One row for a group of identifier types both records carry.
     *
     * <p>Conflict wins where both are true — the same passport and a different tax number is not
     * a near-match with a caveat, it is a contradiction, and the number a reviewer sees should say
     * so rather than averaging the two into something reassuring.
     */
    private MatchSignal signalFor(SubjectFacts left, SubjectFacts right, Set<String> types,
                                  MatchSignalCode code, double agrees, double conflicts) {
        if (types.isEmpty()) {
            return MatchSignal.unavailable(code);
        }
        boolean anyConflict = types.stream().anyMatch(type ->
                !left.nationalIdentifiers().get(type).equals(right.nationalIdentifiers().get(type)));
        return anyConflict
                ? MatchSignal.conflicts(code, conflicts)
                : MatchSignal.agrees(code, agrees);
    }

    /**
     * A field both records must hold before it can agree or disagree.
     *
     * <p>One side blank is a gap and not a disagreement, which is the same rule the identifiers
     * follow and the right one in a country where most fields are empty most of the time. Compared
     * case- and space-insensitively, because "Transport et logistique" and "TRANSPORT ET
     * LOGISTIQUE " are one answer typed twice.
     */
    private MatchSignal equality(String left, String right, MatchSignalCode code,
                                 double agrees, double conflicts) {
        if (!bothPresent(left, right)) {
            return MatchSignal.unavailable(code);
        }
        return left.trim().equalsIgnoreCase(right.trim())
                ? MatchSignal.agrees(code, agrees)
                : MatchSignal.conflicts(code, conflicts);
    }

    /**
     * The street, which only ever counts for a match and never against one.
     *
     * <p>A differing address is reported as neutral rather than as a conflict, and the distinction
     * is the point: a conflict tells a reviewer something was found against the pair, and what was
     * actually found is that two people typed an address differently.
     */
    private MatchSignal addressSignal(SubjectFacts left, SubjectFacts right) {
        if (!bothPresent(left.streetAddress(), right.streetAddress())) {
            return MatchSignal.unavailable(MatchSignalCode.SIMILAR_ADDRESS);
        }
        double similarity = similarity(left.streetAddress().toLowerCase(java.util.Locale.ROOT),
                right.streetAddress().toLowerCase(java.util.Locale.ROOT));
        return similarity >= SIMILAR_ADDRESS_THRESHOLD
                ? MatchSignal.agrees(MatchSignalCode.SIMILAR_ADDRESS, SIMILAR_ADDRESS * similarity)
                : MatchSignal.neutral(MatchSignalCode.SIMILAR_ADDRESS);
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
     *
     * <p>The comma was added with the address comparison, and it was measured rather than assumed:
     * "12 av. Kasa-Vubu" against "12, avenue Kasa Vubu" scored 0.750 with the comma left in and
     * 0.789 with it flattened, because "12," and "12" are two tokens until it goes. Harmless to
     * names, which rarely carry one and are not distinguished by it when they do.
     */
    private String flattenPunctuation(String name) {
        return name.replaceAll("[-'’.,]", " ").replaceAll("\\s+", " ").trim();
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
