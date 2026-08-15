package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.notifications.NotificationService;
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
 * A watch is a standing inquiry, and these tests are mostly about it staying one.
 *
 * <p>The feature is easy to build badly in a way nobody notices: a live feed that fires the moment
 * a rival declares, bypassing the rate limit because it is "internal", writing no audit row because
 * nobody asked. Each of those would hand a watcher something an inquiry never gives them. What is
 * asserted below is the opposite — that watching costs what asking costs, and discloses what asking
 * discloses.
 */
@RequiresDocker
class WatchlistTest extends AbstractIntegrationTest {

    private static final UUID WATCHER = UUID.randomUUID();

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private WatchlistService watchlist;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private NotificationService notifications;

    private UUID bank;
    private UUID vodacom;
    private UUID orange;
    private String rccm;

    @BeforeEach
    void setUp() {
        bank = tenants.save(new Tenant("Watch Bank", "w-bank-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        vodacom = tenants.save(new Tenant("Watch A", "w-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        orange = tenants.save(new Tenant("Watch B", "w-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a watch has to say why the company is being monitored")
    void aWatchNeedsAPurpose() {
        UUID subject = declare(vodacom);

        // The same rule as a single inquiry, and it matters more here: an inquiry is one question
        // on one afternoon, a watch is a decision to keep asking for a year.
        assertThatThrownBy(() -> TenantContext.runAsResult(bank,
                () -> watchlist.watch(subject, "   ", WATCHER)))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Say why");
    }

    @Test
    @DisplayName("watching the same company twice is refused")
    void oneWatchPerCompany() {
        UUID subject = declare(vodacom);
        TenantContext.runAs(bank, () ->
                watchlist.watch(subject, "Credit facility under review.", WATCHER));

        // Two would double every notification and let two people at one institution disagree about
        // why it is being monitored.
        assertThatThrownBy(() -> TenantContext.runAsResult(bank,
                () -> watchlist.watch(subject, "Credit facility under review.", WATCHER)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the first sweep is a baseline, not news")
    void theFirstSweepSaysNothing() {
        UUID subject = declare(vodacom);
        TenantContext.runAs(bank, () ->
                watchlist.watch(subject, "Credit facility under review.", WATCHER));

        WatchlistService.Sweep first =
                TenantContext.runAsResult(bank, () -> watchlist.sweep(WATCHER));

        // Otherwise every watch fires a notification on the night it was created, which teaches
        // whoever reads them that the first one means nothing — and then that the rest might not
        // either.
        assertThat(first.watched()).isEqualTo(1);
        assertThat(first.changed()).isZero();
        assertThat(unread()).isZero();
    }

    @Test
    @DisplayName("a second institution reporting is the change worth telling somebody about")
    void aChangeIsReported() {
        UUID subject = declare(vodacom);
        TenantContext.runAs(bank, () ->
                watchlist.watch(subject, "Credit facility under review.", WATCHER));
        TenantContext.runAs(bank, () -> watchlist.sweep(WATCHER));

        declare(orange);

        WatchlistService.Sweep second =
                TenantContext.runAsResult(bank, () -> watchlist.sweep(WATCHER));

        assertThat(second.changed()).isEqualTo(1);
        assertThat(unread())
                .as("the watcher is told the count moved, and never which institution moved it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("nothing changing means nothing is said, however often it is swept")
    void aQuietSweepIsSilent() {
        UUID subject = declare(vodacom);
        TenantContext.runAs(bank, () ->
                watchlist.watch(subject, "Credit facility under review.", WATCHER));

        TenantContext.runAs(bank, () -> watchlist.sweep(WATCHER));
        TenantContext.runAs(bank, () -> watchlist.sweep(WATCHER));
        TenantContext.runAs(bank, () -> watchlist.sweep(WATCHER));

        // A watchlist that repeated itself nightly would be unread within a week.
        assertThat(unread()).isZero();
    }

    @Test
    @DisplayName("one operator cannot see what another is watching")
    void watchesDoNotCrossOperators() {
        UUID subject = declare(vodacom);
        TenantContext.runAs(bank, () ->
                watchlist.watch(subject, "Credit facility under review.", WATCHER));

        // Which companies a rival is worried about is a commercial intention rather than a fact
        // about a debtor, and the exchange exists to share the second kind of thing only.
        assertThat(TenantContext.runAsResult(orange, () -> watchlist.list())).isEmpty();
        assertThat(TenantContext.runAsResult(bank, () -> watchlist.list())).hasSize(1);
    }

    @Test
    @DisplayName("removing a watch removes it, rather than leaving a note that it stopped")
    void unwatchingDeletes() {
        UUID subject = declare(vodacom);
        UUID watchId = TenantContext.runAsResult(bank,
                () -> watchlist.watch(subject, "Credit facility under review.", WATCHER)).id();

        TenantContext.runAs(bank, () -> watchlist.unwatch(watchId, WATCHER));

        // What it observed is in the audit trail. A row saying "not watching this any more" is
        // personal data kept for no reason.
        assertThat(TenantContext.runAsResult(bank, () -> watchlist.list())).isEmpty();
    }

    private long unread() {
        return TenantContext.runAsResult(bank, () -> notifications.unreadCountFor(WATCHER));
    }

    /** Declares against one shared company, identified by an RCCM so the sweep can resolve it. */
    private UUID declare(UUID operator) {
        return TenantContext.runAsResult(operator, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                "Kasai Minerals " + rccm.substring(rccm.length() - 8),
                Subject.SubjectType.BUSINESS, null, "CD",
                new BigDecimal("900.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(120), true), null).record().getSubject().getId());
    }
}
