package ai.dival.dip.modules.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comparing two records, checked without a database.
 *
 * <p>The case that matters most here is an individual, and that is not an accident of the
 * examples. A company in the DRC has an RCCM; the exchange can join two records about a company
 * the moment both operators supply it. A person has no such number — there is no identifier every
 * bank, operator and utility holds for the same individual — so for people the platform is
 * reduced to comparing names and whatever else came in the file, which is usually nothing.
 *
 * <p>That is the honest position and these tests hold it in both directions: the comparison has to
 * be good enough to put a plausible pair in front of a reviewer, and nowhere near good enough to
 * merge one without them.
 */
class MatchScorerTest {

    private final MatchScorer scorer = new MatchScorer();

    @Test
    @DisplayName("Jean-Pierre Kabamba and Jean Pierre Kabamba reach a reviewer")
    void theCaseTheFeatureExistsFor() {
        MatchAssessment assessment = scorer.compare(
                person("jean-pierre kabamba", "CD", null),
                person("jean pierre kabamba", "CD", null));

        // The first version of the weights put this at 0.31 — under the threshold, so the one
        // pair the whole feature is about would never have appeared in the queue. Two bars were
        // being confused: worth a glance, and enough to merge. Only the first is set here.
        assertThat(assessment.worthReviewing()).isTrue();
        // Exact, not merely similar: the hyphen is flattened before the two are compared, so this
        // is one name written twice rather than two names that resemble each other.
        assertThat(signal(assessment, MatchSignalCode.EXACT_NAME).verdict())
                .isEqualTo(MatchSignal.Verdict.AGREES);
    }

    @Test
    @DisplayName("and do not reach anywhere near confident, because a name is all there is")
    void aNameAloneIsNeverStrong() {
        MatchAssessment assessment = scorer.compare(
                person("jean-pierre kabamba", "CD", null),
                person("jean pierre kabamba", "CD", null));

        // The protection that lets the threshold above be generous. Nothing in this system merges
        // on a number; the number decides whether somebody is asked to look.
        assertThat(assessment.confidence()).isLessThan(MatchAssessment.STRONG_CONFIDENCE);
    }

    @Test
    @DisplayName("a hyphen is a typing convention, not a different name")
    void punctuationIsNotEvidence() {
        double hyphenated = scorer.compare(
                person("jean-pierre kabamba", "CD", null),
                person("jean pierre kabamba", "CD", null)).confidence();
        double identical = scorer.compare(
                person("jean pierre kabamba", "CD", null),
                person("jean pierre kabamba", "CD", null)).confidence();

        // Not merely close — the same. The token comparison is where this bites: with the hyphen
        // intact one side has two words and the other three, and the overlap halves.
        assertThat(hyphenated).isEqualTo(identical);
    }

    @Test
    @DisplayName("a date of birth is what actually resolves a person, and both files would need it")
    void adateOfBirthIsWorthMoreThanAnySpelling() {
        LocalDate born = LocalDate.of(1979, 4, 2);
        MatchAssessment withoutIt = scorer.compare(
                person("jean pierre kabamba", "CD", null),
                person("jean pierre kabamba", "CD", null));
        MatchAssessment withIt = scorer.compare(
                person("jean pierre kabamba", "CD", born),
                person("jean pierre kabamba", "CD", born));

        assertThat(withIt.confidence()).isGreaterThan(withoutIt.confidence());
        assertThat(signal(withoutIt, MatchSignalCode.SAME_DATE_OF_BIRTH).verdict())
                .as("not held by either record, which is not the same as not matching")
                .isEqualTo(MatchSignal.Verdict.UNAVAILABLE);
    }

    @Test
    @DisplayName("two different dates of birth sink a match however alike the names are")
    void aConflictOutweighsTheName() {
        MatchAssessment assessment = scorer.compare(
                person("jean pierre kabamba", "CD", LocalDate.of(1979, 4, 2)),
                person("jean pierre kabamba", "CD", LocalDate.of(1991, 11, 30)));

        // Namesakes exist, and in a country where the surname pool is not enormous they exist in
        // quantity. One hard disagreement has to be able to beat every soft agreement at once, or
        // the queue fills with father-and-son pairs at high confidence.
        assertThat(assessment.worthReviewing()).isFalse();
    }

    @Test
    @DisplayName("a company whose RCCM changed is shown to a reviewer rather than hidden from one")
    void aChangedRegistrationIsAdvisoryRatherThanDecisive() {
        MatchAssessment assessment = scorer.compare(
                business("grand horizon sarl", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001")),
                business("grand horizon sarl", Map.of("RCCM", "CD/KIN/RCCM/19-B-7788")));

        // Counsel, August 2026: an RCCM is reissued when a company amends its statutes or adds
        // capital. Until then this conflict outweighed any agreement, so an exact name plus two
        // register numbers scored zero after clamping and the pair was invisible to the queue for
        // ever. The rule written to prevent a false merge was reliably hiding the true one.
        assertThat(signal(assessment, MatchSignalCode.SHARED_REGISTER_NUMBER).verdict())
                .isEqualTo(MatchSignal.Verdict.CONFLICTS);
        assertThat(assessment.worthReviewing())
                .as("0.55 for the name, less 0.15, plus 0.05 for the type — a person looks")
                .isTrue();
        assertThat(assessment.confidence())
                .as("and nowhere near enough to be treated as settled")
                .isLessThan(MatchAssessment.STRONG_CONFIDENCE);
    }

    @Test
    @DisplayName("but a conflicting tax number still ends the discussion")
    void aConflictingTaxNumberIsStillDecisive() {
        // The reason the register number had to be split out rather than softened in place. A tax
        // number is not reissued because a company amended its statutes, so two different ones are
        // two taxpayers — and softening every identifier together would have let two genuinely
        // distinct businesses trading under one name sit in the queue for ever.
        MatchAssessment assessment = scorer.compare(
                business("grand horizon sarl",
                        Map.of("RCCM", "CD/KIN/RCCM/14-B-4001", "TAX_NUMBER", "A0123456X")),
                business("grand horizon sarl",
                        Map.of("RCCM", "CD/KIN/RCCM/19-B-7788", "TAX_NUMBER", "A9876543Z")));

        assertThat(signal(assessment, MatchSignalCode.SHARED_NATIONAL_IDENTIFIER).verdict())
                .isEqualTo(MatchSignal.Verdict.CONFLICTS);
        assertThat(assessment.worthReviewing()).isFalse();
    }

    @Test
    @DisplayName("a changed RCCM is survivable when a tax number carries the match")
    void aTaxNumberCarriesAChangedRegistration() {
        // Counsel's own prescription: match on the denomination, the sector, the address, and the
        // RCCM *and/or* the tax number. This is the and/or doing its work — the company amended
        // its statutes, took a new register number, and is still the same taxpayer.
        MatchAssessment assessment = scorer.compare(
                business("grand horizon sarl",
                        Map.of("RCCM", "CD/KIN/RCCM/14-B-4001", "TAX_NUMBER", "A0123456X")),
                business("grand horizon sarl",
                        Map.of("RCCM", "CD/KIN/RCCM/19-B-7788", "TAX_NUMBER", "A0123456X")));

        assertThat(assessment.confidence())
                .isGreaterThanOrEqualTo(MatchAssessment.STRONG_CONFIDENCE);
    }

    @Test
    @DisplayName("the two sides of a register number are deliberately not mirror images")
    void agreementAndDisagreementWeighDifferently() {
        MatchAssessment agreeing = scorer.compare(
                business("alpha sarl", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001")),
                business("beta sprl", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001")));
        MatchAssessment conflicting = scorer.compare(
                business("alpha sarl", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001")),
                business("alpha sarl", Map.of("RCCM", "CD/KIN/RCCM/19-B-7788")));

        // The asymmetry is the finding, not an oversight. The same number on two records is one
        // company; two different numbers might be two companies or one company either side of a
        // change of statutes, and the model must not claim to know which. Two entirely different
        // names sharing a register number therefore outweigh one identical name split across two.
        assertThat(agreeing.confidence()).isGreaterThan(conflicting.confidence());
    }

    @Test
    @DisplayName("the same RCCM on two subjects is a duplicate that got in before the number did")
    void aSharedRegistrationIsNearlyDecisive() {
        MatchAssessment assessment = scorer.compare(
                business("grand horizon sarl", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001")),
                business("grand horizon", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001")));

        // National identifiers are globally unique in this registry, so two subjects holding one
        // is not a coincidence — it is a duplicate created before somebody supplied the number.
        // Agreement kept its full weight when the conflict lost most of its own.
        assertThat(assessment.confidence())
                .isGreaterThanOrEqualTo(MatchAssessment.STRONG_CONFIDENCE);
    }

    @Test
    @DisplayName("a dropped legal suffix is not a different company")
    void aMissingSuffixIsNotADifference() {
        MatchAssessment assessment = scorer.compare(
                business("dmark drc sarl", Map.of()),
                business("dmark drc", Map.of()));

        // Edit distance scores this badly — five deleted characters against a short string — and
        // the token comparison is what saves it. This is why there are two and the larger wins.
        assertThat(signal(assessment, MatchSignalCode.SIMILAR_NAME).verdict())
                .isEqualTo(MatchSignal.Verdict.AGREES);
        assertThat(assessment.worthReviewing()).isTrue();
    }

    @Test
    @DisplayName("two unrelated companies do not reach the queue at all")
    void strangersAreNotCandidates() {
        MatchAssessment assessment = scorer.compare(
                business("grand horizon sarl", Map.of()),
                business("kivu logistique", Map.of()));

        // A review queue nobody can empty is a review queue nobody opens.
        assertThat(assessment.worthReviewing()).isFalse();
    }

    @Test
    @DisplayName("a company name carries far more than a personal one")
    void namesAreNotWorthTheSameThing() {
        double asBusinesses = scorer.compare(
                business("mutombo kalala", Map.of()),
                business("mutombo kalala", Map.of())).confidence();
        double asPeople = scorer.compare(
                person("mutombo kalala", null, null),
                person("mutombo kalala", null, null)).confidence();

        // A registered trading name is chosen to be distinctive and checked for collision at
        // registration. A personal name is neither, and the profiled export had 48 names on more
        // than one account inside one operator's book.
        assertThat(asBusinesses).isGreaterThan(asPeople);
    }

    @Test
    @DisplayName("the three signals nobody can evaluate are reported, not omitted")
    void theGapsAreOnTheReport() {
        MatchAssessment assessment = scorer.compare(
                person("jean pierre kabamba", "CD", null),
                person("jean pierre kabamba", "CD", null));

        // The ask to operators, made every time somebody opens a case. A second phone number, a
        // city and an address would move a reviewer further than any refinement of the name
        // comparison could — and the only way that gets asked for is if the gap is on the screen.
        for (MatchSignalCode code : EnumSet.of(MatchSignalCode.SAME_SECONDARY_PHONE,
                MatchSignalCode.SAME_CITY, MatchSignalCode.SIMILAR_ADDRESS)) {
            assertThat(signal(assessment, code).verdict())
                    .as("%s", code)
                    .isEqualTo(MatchSignal.Verdict.UNAVAILABLE);
            assertThat(signal(assessment, code).weight()).isZero();
        }
    }

    @Test
    @DisplayName("every signal appears in every comparison, however little there was to compare")
    void theListIsAlwaysTheSame() {
        MatchAssessment thin = scorer.compare(
                person("a bb", null, null), person("c dd", null, null));
        MatchAssessment rich = scorer.compare(
                business("grand horizon sarl", Map.of("RCCM", "X", "TAX_NUMBER", "Y")),
                business("grand horizon sarl", Map.of("RCCM", "X", "TAX_NUMBER", "Y")));

        // A list that shortened when data was missing would let a reviewer mistake an unasked
        // question for an answered one. The two name codes are the single exception: a pair of
        // names is either the same name or a similar one, never both, so exactly one is reported.
        Set<MatchSignalCode> exceptNames = EnumSet.complementOf(
                EnumSet.of(MatchSignalCode.EXACT_NAME, MatchSignalCode.SIMILAR_NAME));

        assertThat(thin.signals()).hasSameSizeAs(rich.signals());
        for (MatchAssessment assessment : List.of(thin, rich)) {
            assertThat(assessment.signals().stream().map(MatchSignal::code))
                    .containsAll(exceptNames)
                    .hasSize(exceptNames.size() + 1);
        }
    }

    @Test
    @DisplayName("different account numbers at two institutions are the normal state of a real match")
    void differentAccountNumbersProveNothing() {
        MatchAssessment shown = scorer.compare(
                new SubjectFacts(false, "jean pierre kabamba", "CD", null, Map.of(), true,
                        null, null, null),
                new SubjectFacts(false, "jean pierre kabamba", "CD", null, Map.of(), true,
                        null, null, null));

        // Every genuine cross-operator match has two different customer numbers, because operators
        // number their own customers from one upwards. Reading that as evidence against would
        // refuse all of them. Shown because a reviewer will notice it; weighted at nothing.
        MatchSignal accounts = signal(shown, MatchSignalCode.DIFFERENT_ACCOUNT_REFERENCES);
        assertThat(accounts.verdict()).isEqualTo(MatchSignal.Verdict.NEUTRAL);
        assertThat(accounts.weight()).isZero();
    }

    @Test
    @DisplayName("confidence never leaves the nought-to-one range")
    void theScaleHolds() {
        MatchAssessment best = scorer.compare(
                business("grand horizon sarl", Map.of("RCCM", "X", "TAX_NUMBER", "Y")),
                business("grand horizon sarl", Map.of("RCCM", "X", "TAX_NUMBER", "Y")));
        MatchAssessment worst = scorer.compare(
                person("a", "CD", LocalDate.of(1979, 4, 2)),
                person("zzzzzzzz", "RW", LocalDate.of(1991, 1, 1)));

        assertThat(best.confidence()).isBetween(0.0, 1.0);
        assertThat(worst.confidence()).isBetween(0.0, 1.0);
    }

    // --- what counsel asked for, and what it is for -------------------------

    @Test
    @DisplayName("two homonymous companies in different cities and trades are separated again")
    void sectorAndCitySeparateWhatTheRegisterNumberNoLongerCan() {
        // The pair the softened register-number rule started putting in front of a reviewer. Two
        // companies of one name and two RCCMs used to score zero and vanish; they now score 0.45
        // and land in the queue, because on a name and a number alone nothing can tell them from
        // one company that re-registered.
        //
        // Sector and city are what tell them apart, which is the whole reason counsel named them.
        MatchAssessment assessment = scorer.compare(
                profiled("grand horizon sarl", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001"),
                        "Transport et logistique", "Kinshasa", "12 av. Kasa-Vubu"),
                profiled("grand horizon sarl", Map.of("RCCM", "CD/KIN/RCCM/19-B-7788"),
                        "Pharmacie", "Goma", "45 rue du Marché"));

        assertThat(assessment.worthReviewing())
                .as("0.55 name − 0.15 register − 0.15 sector − 0.25 city + 0.05 type")
                .isFalse();
    }

    @Test
    @DisplayName("and one company that re-registered reads as one company")
    void sectorAndAddressCarryARegistrationChange() {
        // Same two records, same conflicting RCCMs, everything else agreeing. This is the case the
        // old rule could never surface at all, and the case these three columns exist to settle.
        MatchAssessment assessment = scorer.compare(
                profiled("grand horizon sarl", Map.of("RCCM", "CD/KIN/RCCM/14-B-4001"),
                        "Transport et logistique", "Kinshasa", "12 av. Kasa-Vubu"),
                profiled("grand horizon sarl", Map.of("RCCM", "CD/KIN/RCCM/19-B-7788"),
                        "Transport et logistique", "Kinshasa", "12, avenue Kasa Vubu"));

        // 0.83, which is high and is deliberately not asserted as "strong". The abbreviation costs
        // it: "av." against "avenue" holds the address comparison to 0.789 rather than 1.0, worth
        // about five points. Tuning the weights until this cleared 0.85 would be fitting the model
        // to one fixture. Nothing merges at any confidence anyway — the number decides whether a
        // person is asked to look, and this pair very much is.
        assertThat(assessment.confidence()).isGreaterThan(0.80);
        assertThat(assessment.worthReviewing()).isTrue();
    }

    @Test
    @DisplayName("a street written two ways is one street")
    void addressComparisonSurvivesPunctuation() {
        // "12 av. Kasa-Vubu" against "12, avenue Kasa Vubu" is one address typed by two clerks.
        // A comparison that called this a difference would be measuring a house style.
        MatchAssessment assessment = scorer.compare(
                profiled("alpha sarl", Map.of(), null, null, "12 av. Kasa-Vubu"),
                profiled("alpha sarl", Map.of(), null, null, "12, avenue Kasa Vubu"));

        assertThat(signal(assessment, MatchSignalCode.SIMILAR_ADDRESS).verdict())
                .isEqualTo(MatchSignal.Verdict.AGREES);
    }

    @Test
    @DisplayName("a different street is shown and weighed at nothing, unlike a different city")
    void aDifferentStreetIsNotEvidenceAgainst() {
        MatchAssessment assessment = scorer.compare(
                profiled("alpha sarl", Map.of(), null, "Kinshasa", "12 av. Kasa-Vubu"),
                profiled("alpha sarl", Map.of(), null, "Kinshasa", "78 boulevard du 30 Juin"));

        // Neutral rather than conflicting, and the distinction is not cosmetic. A conflict tells a
        // reviewer something was found against the pair; what was actually found is that a company
        // moved, or that two clerks recorded two of its premises. Weighing that against a match
        // would refuse genuine pairs on the strength of somebody's filing.
        MatchSignal street = signal(assessment, MatchSignalCode.SIMILAR_ADDRESS);
        assertThat(street.verdict()).isEqualTo(MatchSignal.Verdict.NEUTRAL);
        assertThat(street.weight()).isZero();

        // The city, which does compare as an equality, is where a real geographic disagreement is
        // reported — and here it agrees.
        assertThat(signal(assessment, MatchSignalCode.SAME_CITY).verdict())
                .isEqualTo(MatchSignal.Verdict.AGREES);
    }

    @Test
    @DisplayName("one side holding a sector and the other not is a gap, not a disagreement")
    void aMissingFieldIsNotAConflict() {
        // The ordinary state of the registry. One operator files against the published template
        // and supplies everything; another declares from a billing system and supplies none of it.
        // Reading that as evidence against would penalise the company for the platform's own
        // uneven coverage.
        MatchAssessment assessment = scorer.compare(
                profiled("alpha sarl", Map.of(), "Transport", "Kinshasa", "12 av. Kasa-Vubu"),
                profiled("alpha sarl", Map.of(), null, null, null));

        for (MatchSignalCode code : List.of(MatchSignalCode.SAME_SECTOR,
                MatchSignalCode.SAME_CITY, MatchSignalCode.SIMILAR_ADDRESS)) {
            assertThat(signal(assessment, code).verdict())
                    .as("%s", code)
                    .isEqualTo(MatchSignal.Verdict.UNAVAILABLE);
            assertThat(signal(assessment, code).weight()).isZero();
        }
    }

    @Test
    @DisplayName("a sector recorded in two letter cases is one sector")
    void sectorComparisonIgnoresCaseAndPadding() {
        MatchAssessment assessment = scorer.compare(
                profiled("alpha sarl", Map.of(), "Transport et logistique", null, null),
                profiled("alpha sarl", Map.of(), "TRANSPORT ET LOGISTIQUE ", null, null));

        assertThat(signal(assessment, MatchSignalCode.SAME_SECTOR).verdict())
                .isEqualTo(MatchSignal.Verdict.AGREES);
    }

    private static SubjectFacts person(String name, String nationality, LocalDate born) {
        return new SubjectFacts(false, name, nationality, born, Map.of(), false, null, null, null);
    }

    private static SubjectFacts business(String name, Map<String, String> identifiers) {
        return new SubjectFacts(true, name, "CD", null, identifiers, false, null, null, null);
    }

    /** A company the registry knows something about beyond its name and its documents. */
    private static SubjectFacts profiled(String name, Map<String, String> identifiers,
                                         String sector, String city, String street) {
        return new SubjectFacts(true, name, "CD", null, identifiers, false, sector, city, street);
    }

    private static MatchSignal signal(MatchAssessment assessment, MatchSignalCode code) {
        return assessment.signals().stream()
                .filter(s -> s.code() == code)
                .findFirst()
                .orElseThrow(() -> new AssertionError(code + " is missing from the comparison"));
    }
}
