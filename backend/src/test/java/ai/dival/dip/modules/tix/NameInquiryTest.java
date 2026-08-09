package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asking the exchange by name, and the four ways that has to stay a lookup.
 *
 * <p>The reason a name was refused until now is that a name box over a shared registry is an
 * enumeration tool. What makes this one not: the match is exact rather than a prefix, two
 * candidates stop the answer rather than producing a list, a personal name never clears the
 * confidence threshold on its own, and the whole thing runs on the inquiry path with its stated
 * purpose, its rate limit and its audit row.
 */
@RequiresDocker
class NameInquiryTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private ExchangeService exchange;

    private UUID reporter;
    private UUID enquirer;
    private String suffix;

    @BeforeEach
    void setUp() {
        reporter = tenants.save(new Tenant("Name Reporter", "nr-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        enquirer = tenants.save(new Tenant("Name Enquirer", "ne-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void declare(String name, Subject.SubjectType type, String identifier) {
        TenantContext.runAs(reporter, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, identifier)),
                name, type, type == Subject.SubjectType.INDIVIDUAL
                        ? LocalDate.of(1990, 5, 12) : null,
                "CD", new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true), null));
    }

    private InquiryResult askByName(String name) {
        return TenantContext.runAsResult(enquirer, () -> exchange.inquire(new InquiryRequest(
                null, name, "Credit application, file 4471"), UUID.randomUUID()));
    }

    // --- it answers ---------------------------------------------------------

    @Test
    @DisplayName("a registered trading name resolves the business")
    void businessNameResolves() {
        String name = "Grand Horizon SARL " + suffix;
        declare(name, Subject.SubjectType.BUSINESS, "CD-" + UUID.randomUUID());

        InquiryResult result = askByName(name);

        // A trading name is a public register entry: distinctive, collision-checked, and read off
        // a document. Refusing to answer without an RCCM number would refuse a question the
        // exchange can answer.
        assertThat(result.outcome()).isEqualTo(InquiryResult.Outcome.OUTSTANDING_DEBT);
        assertThat(result.subjectId()).isNotNull();
    }

    @Test
    @DisplayName("accents and spacing do not decide whether it resolves")
    void nameIsNormalisedBeforeMatching() {
        declare("Société  Générale d'Alimentation " + suffix,
                Subject.SubjectType.BUSINESS, "CD-" + UUID.randomUUID());

        assertThat(askByName("societe generale d'alimentation " + suffix).outcome())
                .isEqualTo(InquiryResult.Outcome.OUTSTANDING_DEBT);
    }

    // --- and the four ways it stays a lookup --------------------------------

    @Test
    @DisplayName("a personal name alone is never enough, however unique it is")
    void personalNamesNeverClearTheThreshold() {
        String name = "Jean-Baptiste Kabila " + suffix;
        declare(name, Subject.SubjectType.INDIVIDUAL, "CD-" + UUID.randomUUID());

        InquiryResult result = askByName(name);

        // The profiled export had 48 names on more than one account inside a single operator's
        // book. Across a national registry it can only be worse, so this is the matcher being
        // right rather than failing.
        assertThat(result.outcome()).isEqualTo(InquiryResult.Outcome.REVIEW_REQUIRED);
        assertThat(result.subjectId())
                .as("review carries no subject id, so it cannot be used to confirm existence")
                .isNull();
    }

    @Test
    @DisplayName("two businesses with the same name stop the answer rather than producing a list")
    void ambiguityRefusesToGuess() {
        String name = "Établissements Mwamba " + suffix;
        declare(name, Subject.SubjectType.BUSINESS, "CD-" + UUID.randomUUID());
        declare(name, Subject.SubjectType.BUSINESS, "CD-" + UUID.randomUUID());

        InquiryResult result = askByName(name);

        // Choosing one would be a guess presented as an answer. Saying "2 matches" would be the
        // first sentence of an enumeration.
        assertThat(result.outcome()).isEqualTo(InquiryResult.Outcome.REVIEW_REQUIRED);
        assertThat(result.subjectId()).isNull();
        assertThat(result.statuses()).isEmpty();
    }

    @Test
    @DisplayName("a prefix is not a match, so the name box cannot be walked one letter at a time")
    void prefixesDoNotMatch() {
        declare("Atlas Distribution SARL " + suffix,
                Subject.SubjectType.BUSINESS, "CD-" + UUID.randomUUID());

        // The whole difference between a lookup and enumeration. A prefix search answers "how
        // many businesses begin with these letters", repeatedly, cheaply.
        assertThat(askByName("Atlas").outcome()).isEqualTo(InquiryResult.Outcome.NO_MATCH);
        assertThat(askByName("Atlas Distribution").outcome())
                .isEqualTo(InquiryResult.Outcome.NO_MATCH);
    }

    @Test
    @DisplayName("an identifier that resolves wins over a name that disagrees")
    void identifiersAreTriedFirst() {
        // The suffix is folded into the word rather than appended as a separate one, and that is
        // load-bearing. Every other fixture here writes "Something SARL <suffix>", which gives the
        // two names a token in common — and IdentityMatcher only penalises a name that shares
        // *nothing* with the stored one. The first version of this test did that, so the
        // disagreement it was written to create never happened, confidence stayed at the strong
        // identifier's 0.90, and the assertion failed for a reason that had nothing to do with
        // the code under test.
        String held = "Kinlogistique" + suffix;
        String other = "Autresociete" + suffix;
        assertThat(Subject.normalizeName(held).split(" "))
                .as("the two names must share no token, or no conflict penalty is applied")
                .doesNotContainAnyElementsOf(List.of(Subject.normalizeName(other).split(" ")));

        String document = "CD-" + UUID.randomUUID();
        declare(held, Subject.SubjectType.BUSINESS, document);
        declare(other, Subject.SubjectType.BUSINESS, "CD-" + UUID.randomUUID());

        InquiryResult result = TenantContext.runAsResult(enquirer, () ->
                exchange.inquire(new InquiryRequest(
                        List.of(new InquiryRequest.SubmittedIdentifier(
                                IdentifierType.NATIONAL_ID, document)),
                        other,
                        "Credit application"), UUID.randomUUID()));

        // Resolution fell to the document, so the name cannot be used to steer the answer onto a
        // different subject. The disagreement costs confidence, which is the matcher's job.
        assertThat(result.outcome()).isEqualTo(InquiryResult.Outcome.REVIEW_REQUIRED);
    }

    // --- the shape of the request -------------------------------------------

    @Test
    @DisplayName("a request with neither an identifier nor a usable name is not resolvable")
    void nothingToResolve() {
        assertThat(new InquiryRequest(null, null, "why").isResolvable()).isFalse();
        assertThat(new InquiryRequest(List.of(), "   ", "why").isResolvable()).isFalse();
        // Too short to be a name and long enough to match a great deal of the registry.
        assertThat(new InquiryRequest(List.of(), "SA", "why").isResolvable()).isFalse();

        assertThat(new InquiryRequest(List.of(), "Atlas", "why").isResolvable()).isTrue();
        assertThat(new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(
                        IdentifierType.RCCM, "CD/KIN/RCCM/1")), null, "why").isResolvable())
                .isTrue();
    }

    @Test
    @DisplayName("identifiers are never null downstream, whatever the caller sent")
    void identifiersDefaultToEmpty() {
        assertThat(new InquiryRequest(null, "Atlas Distribution", "why").identifiers()).isEmpty();
    }
}
