package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.PolicyRefusedException;
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
 * The 360° profile <strong>in the configuration this platform ships in</strong>.
 *
 * <p>No property is overridden here, deliberately. {@link DisclosureProperties} defaults to
 * counting rather than naming, so this class is the shipped deployment, and what it asserts is what
 * every participant was promised: how many institutions report the subject, and never which and
 * never how much. {@code NamedContributorsTest} is the same screen with the switch on.
 *
 * <p>Two operators, because every interesting assertion here needs somebody else's book to fail to
 * disclose. Both declare against the same RCCM, so both resolve to one subject, and whichever of
 * them opens the profile is asking about a company its rival also reports.
 */
@RequiresDocker
class Subject360Test extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private Subject360Service profiles;
    @Autowired
    private DisclosureProperties disclosure;

    private UUID vodacom;
    private UUID orange;
    private String rccm;
    /** Every operator declares against the same RCCM, so they all resolve to this one subject. */
    private UUID subject;

    @BeforeEach
    void setUp() {
        vodacom = tenants.save(new Tenant("Three60 Vodacom", "t60-v-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        orange = tenants.save(new Tenant("Three60 Orange", "t60-o-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the shipped configuration counts institutions and names none of them")
    void shippedConfigurationNamesNobody() {
        // Asserted first and separately from the behaviour below. If somebody ever changes the
        // default, this line fails on its own and says so, rather than a dozen disclosure
        // assertions failing and looking like a bug in the profile.
        assertThat(disclosure.canName())
                .as("the shipped default must be to count institutions, not name them")
                .isFalse();
        assertThat(disclosure.canPrice()).isFalse();

        declare(vodacom, "500.00");
        declare(orange, "1200.00");

        Subject360Service.Subject360 view = openAs(vodacom);

        assertThat(view.overview().institutionCount()).isEqualTo(2);
        assertThat(view.contributors()).isEmpty();
        assertThat(view.contributorsWithheld())
                .as("the screen has to be able to say the platform withheld them, rather than "
                        + "render an empty table that looks like nobody else reports this company")
                .isTrue();
        assertThat(view.overview().marketExposure())
                .as("a market total is an amount disclosure with the names removed")
                .isNull();
    }

    @Test
    @DisplayName("no rival operator's name or amount appears anywhere in the response")
    void nothingAboutTheOtherOperatorLeaks() {
        declare(vodacom, "500.00");
        declare(orange, "1200.00");

        // Vodacom asks. It knows its own 500 and must learn nothing about Orange's 1200 beyond
        // the fact that somebody else is reporting.
        Subject360Service.Subject360 view = openAs(vodacom);

        assertThat(view.toString())
                .as("an operator id or name would identify a rival's customer relationship")
                .doesNotContain(orange.toString())
                .doesNotContain("Three60 Orange");

        // Amounts asserted structurally rather than as substrings of the rendered record. The
        // fixture's RCCM carries eight hex characters and hex can spell any decimal string, so
        // doesNotContain("1200") is a check that passes for the wrong reason often enough to be
        // worthless — and worse, could fail for the wrong reason too.
        assertThat(view.overview().yourExposure())
                .as("the asker's own figure is theirs to see, and is only theirs")
                .isEqualTo("500.00");
        assertThat(view.overview().marketExposure()).isNull();
        assertThat(view.contributors()).isEmpty();
    }

    @Test
    @DisplayName("every overview figure is the asker's own book, and the count is the exception")
    void theOverviewIsYourOwnBook() {
        declare(vodacom, "500.00");
        declare(vodacom, "250.00");
        declare(orange, "9999.00");

        Subject360Service.Subject360 view = openAs(vodacom);

        assertThat(view.overview().yourExposure()).isEqualTo("750.00");
        assertThat(view.overview().yourRecords()).isEqualTo(2);
        assertThat(view.overview().openAccounts()).isEqualTo(2);
        assertThat(view.overview().institutionCount())
                .as("the one figure on this screen that is not the asker's own")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("signals are derived from the figures beside them, not asserted")
    void signalsFollowTheFigures() {
        declare(vodacom, "500.00");
        declare(vodacom, "250.00");
        declare(orange, "300.00");

        List<Subject360Service.Signal> signals = openAs(vodacom).signals();

        assertThat(signals).contains(
                Subject360Service.Signal.MULTIPLE_OUTSTANDING_OBLIGATIONS,
                Subject360Service.Signal.REPORTED_BY_SEVERAL_INSTITUTIONS);
        assertThat(signals).doesNotContain(
                Subject360Service.Signal.NOTHING_OUTSTANDING_IN_YOUR_BOOK);
    }

    @Test
    @DisplayName("fraud is reported as not assessed, and never as a clean result")
    void fraudIsNeverATick() {
        declare(vodacom, "500.00");

        // The spec this screen was built from asked for "✓ No active fraud alert". There is no
        // fraud detector and there cannot be one: the registry's uniqueness rules make one
        // document under two subjects impossible, so a detector would report nothing forever and
        // a permanent green tick is indistinguishable from a working check that found nothing.
        assertThat(openAs(vodacom).signals())
                .contains(Subject360Service.Signal.FRAUD_NOT_ASSESSED);
    }

    @Test
    @DisplayName("a subject this operator holds nothing about is not found, not refused")
    void notHeldIsIndistinguishableFromNotExisting() {
        // Only Orange declares. Vodacom then asks about a company it holds nothing on: "not
        // yours" would confirm the company exists, and a screen that confirms existence for any
        // id is an enumeration tool with a nice layout.
        declare(orange, "500.00");
        UUID subjectOnlyOrangeHolds = subject;

        assertThatThrownBy(() -> TenantContext.runAsResult(vodacom,
                () -> profiles.assemble(subjectOnlyOrangeHolds, "Credit check", null)))
                .isInstanceOf(SearchService.SubjectNotHeldException.class);
    }

    @Test
    @DisplayName("opening a profile without a stated purpose is refused before it costs an inquiry")
    void aPurposeIsRequired() {
        declare(vodacom, "500.00");
        UUID held = subject;

        assertThatThrownBy(() -> TenantContext.runAsResult(vodacom,
                () -> profiles.assemble(held, "  ", null)))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("the timeline carries the asker's own records and no date belonging to anybody else")
    void theTimelineIsOwnRecordsOnly() {
        declare(vodacom, "500.00");
        declare(orange, "700.00");

        Subject360Service.Subject360 view = openAs(vodacom);

        // One record held, so one obligation event. A timeline is the most tempting place to leak
        // "since when", because a row reading "2025 · second institution began reporting" looks
        // like narrative and is a date attached to a competitor's file.
        assertThat(view.timeline())
                .as("one own record, one dated event")
                .hasSize(1);
        assertThat(view.timeline().get(0).code())
                .isEqualTo(Subject360Service.EventCode.OBLIGATION_FELL_DUE);
    }

    private Subject360Service.Subject360 openAs(UUID operator) {
        return TenantContext.runAsResult(operator,
                () -> profiles.assemble(subject, "Credit application, file 4471", null));
    }

    /**
     * Declares one obligation and remembers the subject it resolved to.
     *
     * <p>The id is taken from the declaration's own return rather than looked up afterwards.
     * Looking it up would need a second tenant-scoped call nested inside the first, and every
     * operator here declares against the same RCCM, so they all resolve to one subject anyway —
     * which is the fixture the whole class rests on.
     */
    private void declare(UUID operator, String amount) {
        subject = TenantContext.runAsResult(operator, () -> debtRecords.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM,
                                rccm)),
                        "Trans-Congo 360 " + rccm.substring(rccm.length() - 8),
                        Subject.SubjectType.BUSINESS, null, "CD",
                        new BigDecimal(amount), "USD", "POSTPAID",
                        LocalDate.now().minusDays(30), true), null)
                .record().getSubject().getId());
    }
}
