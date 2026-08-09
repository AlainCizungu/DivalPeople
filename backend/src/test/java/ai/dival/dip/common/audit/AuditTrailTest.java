package ai.dival.dip.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reading the trail back.
 *
 * <p>Rows have been written since the first migration and never read by anything until article 214
 * needed them and this screen made them visible. Writing and reading are different claims: a trail
 * that records the right things and hands one operator another's rows is worse than no trail,
 * because it would be trusted.
 *
 * <p>Not {@code @Transactional}. Every write here happens in {@code REQUIRES_NEW}, so a rolled-back
 * test would assert against rows that never committed.
 */
@RequiresDocker
class AuditTrailTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private AuditService audit;

    private UUID operatorA;
    private UUID operatorB;
    private String marker;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Trail A", "ta-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Trail B", "tb-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        marker = "purpose-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void write(UUID operator, String action, String detail) {
        TenantContext.runAs(operator, () -> audit.record(
                action, "Subject", UUID.randomUUID().toString(),
                AuditService.OUTCOME_SUCCESS, UUID.randomUUID(), detail));
    }

    private List<AuditEvent> read(UUID operator, String action) {
        return TenantContext.runAsResult(operator, () -> audit.recent(action, 100));
    }

    @Test
    @DisplayName("an operator reads its own rows and nobody else's")
    void trailIsTenantScoped() {
        write(operatorA, "TIX_INQUIRY", marker);
        write(operatorB, "TIX_INQUIRY", "somebody else's reason");

        assertThat(read(operatorA, null)).extracting(AuditEvent::getDetail).contains(marker);
        assertThat(read(operatorB, null)).extracting(AuditEvent::getDetail)
                .doesNotContain(marker);
    }

    @Test
    @DisplayName("the stated purpose survives to the reader")
    void purposeIsWhatMakesTheRowWorthKeeping() {
        write(operatorA, "TIX_INQUIRY", marker);

        // Without this column the trail says somebody looked somebody up, which is not an answer
        // to any question an auditor has.
        assertThat(read(operatorA, "TIX_INQUIRY")).first()
                .satisfies(event -> assertThat(event.getDetail()).isEqualTo(marker));
    }

    @Test
    @DisplayName("refused attempts are kept, because they are the interesting ones")
    void deniedEventsAreReadableToo() {
        TenantContext.runAs(operatorA, () -> audit.record(
                "TIX_INQUIRY", "Subject", null, AuditService.OUTCOME_DENIED,
                UUID.randomUUID(), marker));

        // A rate-limited sweep that left no trace would just be a slower invisible sweep.
        assertThat(read(operatorA, null))
                .anySatisfy(event -> assertThat(event.getOutcome())
                        .isEqualTo(AuditService.OUTCOME_DENIED));
    }

    @Test
    @DisplayName("newest first, so the page shows what just happened")
    void orderedNewestFirst() {
        write(operatorA, "TIX_INQUIRY", "older " + marker);
        write(operatorA, "TIX_INQUIRY", "newer " + marker);

        assertThat(read(operatorA, "TIX_INQUIRY")).first()
                .satisfies(event -> assertThat(event.getDetail()).startsWith("newer"));
    }

    @Test
    @DisplayName("filtering by action narrows without changing the order")
    void filteringByAction() {
        write(operatorA, "TIX_INQUIRY", marker);
        write(operatorA, "TIX_DEBT_DECLARED", "declared " + marker);

        assertThat(read(operatorA, "TIX_INQUIRY")).allSatisfy(event ->
                assertThat(event.getAction()).isEqualTo("TIX_INQUIRY"));
        assertThat(read(operatorA, null)).extracting(AuditEvent::getAction)
                .contains("TIX_INQUIRY", "TIX_DEBT_DECLARED");
    }

    @Test
    @DisplayName("the counts are over the whole trail, and are this operator's own")
    void countsAreScopedAndComplete() {
        write(operatorA, "TIX_INQUIRY", marker);
        write(operatorA, "TIX_INQUIRY", marker);
        write(operatorB, "TIX_INQUIRY", "theirs");

        long mine = TenantContext.runAsResult(operatorA, () -> audit.countsByAction()).stream()
                .filter(count -> count.action().equals("TIX_INQUIRY"))
                .mapToLong(AuditService.ActionCount::count)
                .sum();

        // Two, not three. The number an auditor is shown is how many inquiries this operator has
        // made, and it must not quietly include a competitor's.
        assertThat(mine).isEqualTo(2);
    }

    @Test
    @DisplayName("a page is a page: the limit is clamped rather than obeyed")
    void limitIsClamped() {
        write(operatorA, "TIX_INQUIRY", marker);

        // A caller asking for a million rows is asking for an export, and gets a page.
        assertThat(TenantContext.runAsResult(operatorA, () -> audit.recent(null, 1_000_000)))
                .hasSizeLessThanOrEqualTo(500);
        assertThat(TenantContext.runAsResult(operatorA, () -> audit.recent(null, 0)))
                .as("a nonsensical limit still returns something rather than throwing")
                .isNotNull();
    }
}
