package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.overview.OverviewService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The network counts, asked from the side that can actually get them wrong.
 *
 * <p><strong>Run as {@code dip_app}, and that is the whole point of this file.</strong> Every
 * ordinary integration test here connects as the schema owner, which bypasses row-level security —
 * so a version of {@link NetworkService} that forgot {@code enterExchangeMode()} would return
 * correct network totals in those tests and, in production, silently narrow every count to the
 * caller's own tenant. An operator would be shown a network of one institution, its own subjects,
 * and nought shared, and every figure would look entirely plausible. There is no symptom to notice
 * and nothing to grep for. It has to be tested under the policy or it is not tested.
 *
 * <p><strong>Deltas, not totals.</strong> These figures count the whole database and this suite
 * does not run in a transaction, so any absolute assertion would be a race against whatever else
 * has committed. Each test measures the network, changes it, measures again, and asserts what
 * moved.
 *
 * <p>A CDF floor is not needed — everything here is declared in USD, above the standard floor.
 */
@RequiresDocker
@TestPropertySource(properties = {
        "spring.datasource.username=dip_app",
        "spring.datasource.password=dip_app",
})
class NetworkServiceUnderRlsTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private NetworkService network;
    @Autowired
    private OverviewService overview;

    private UUID operatorA;
    private UUID operatorB;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Network A", "net-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Network B", "net-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- fixtures -----------------------------------------------------------

    /** Declares against a named identifier, so two operators can be made to share a subject. */
    private void declare(UUID operator, String nationalId, String amount) {
        TenantContext.runAs(operator, () -> debtRecords.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.NATIONAL_ID, nationalId)),
                        "Société " + nationalId, Subject.SubjectType.BUSINESS, null, "CD",
                        new BigDecimal(amount), "USD", "POSTPAID",
                        LocalDate.now().minusDays(30), true),
                null));
    }

    private NetworkService.Network seenBy(UUID operator) {
        return TenantContext.runAsResult(operator, () -> network.summarise());
    }

    // --- the boundary -------------------------------------------------------

    @Test
    @DisplayName("the network summary carries counts and cannot carry a name")
    void nothingInTheShapeCanIdentifyAnOperator() {
        // Structural rather than behavioural, deliberately. Asserting that today's response
        // happens to contain no names would pass for as long as nobody adds a field; asserting
        // that the type has nowhere to put one fails the moment somebody tries. The figure this
        // guards is sharedSubjects, which measures overlap between operators and is the closest
        // any published number comes to the boundary the exchange promises.
        RecordComponent[] components = NetworkService.Network.class.getRecordComponents();

        assertThat(components)
                .as("Network gained a component that is not a count. Every figure here is shown "
                        + "to every operator, so a component that can hold a tenant id, an "
                        + "operator name or a subject is a disclosure decision and not a field.")
                .isNotEmpty()
                .allSatisfy(component -> assertThat(component.getType()).isEqualTo(long.class));
    }

    @Test
    @DisplayName("both operators are shown the same network")
    void theNetworkLooksTheSameFromEitherSide() {
        declare(operatorA, "CD-" + UUID.randomUUID(), "900.00");
        declare(operatorB, "CD-" + UUID.randomUUID(), "700.00");

        // Not a tautology under RLS. Without exchange mode each side would see only its own
        // records, so these two would differ — which is precisely the production defect this
        // file exists to catch.
        assertThat(seenBy(operatorA)).isEqualTo(seenBy(operatorB));
    }

    @Test
    @DisplayName("asking the network does not widen the caller's own register")
    void exchangeModeDoesNotLeakIntoTheCallersTransaction() {
        declare(operatorA, "CD-" + UUID.randomUUID(), "500.00");
        declare(operatorB, "CD-" + UUID.randomUUID(), "900.00");
        declare(operatorB, "CD-" + UUID.randomUUID(), "800.00");

        // The overview counts the caller's own register and summarises the network in one
        // response. Exchange mode is SET LOCAL — scoped to a transaction, not to a method — so if
        // NetworkService joined the caller's transaction the flag would stay on and the register
        // would begin counting every operator's records.
        //
        // This passes today for the wrong reason as well as the right one: Java evaluates
        // constructor arguments left to right, so the register happens to be counted before the
        // network is asked. That is not a tenant boundary, it is a coincidence of statement
        // order, and this test exists so that moving one line fails the build instead of
        // publishing a rival's book on the front door.
        OverviewService.Overview seen =
                TenantContext.runAsResult(operatorA, () -> overview.forCaller(true, true));

        assertThat(seen.register().total())
                .as("operator A declared one record; anything more is B's book")
                .isEqualTo(1);
        assertThat(seen.network().subjects())
                .as("and the network section still sees across operators")
                .isGreaterThanOrEqualTo(3);
    }

    // --- what the figures mean ----------------------------------------------

    @Test
    @DisplayName("a subject owing two operators is counted once, and counted as shared")
    void sharedSubjectsCountsTheOverlap() {
        String shared = "CD-" + UUID.randomUUID();
        NetworkService.Network before = seenBy(operatorA);

        declare(operatorA, shared, "1200.00");
        NetworkService.Network afterOne = seenBy(operatorA);

        // One operator is not an overlap. The subject exists in the network; nothing is shared.
        assertThat(afterOne.subjects() - before.subjects()).isEqualTo(1);
        assertThat(afterOne.sharedSubjects() - before.sharedSubjects()).isZero();

        declare(operatorB, shared, "450.00");
        NetworkService.Network afterBoth = seenBy(operatorA);

        // The second declaration adds no subject — the spine is shared, and both operators are
        // talking about one company — but it does make that company an overlap.
        assertThat(afterBoth.subjects() - before.subjects())
                .as("a subject declared by two operators is one subject, not two")
                .isEqualTo(1);
        assertThat(afterBoth.sharedSubjects() - before.sharedSubjects())
                .as("the figure only a shared registry can produce")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("contributing counts operators, however many records each declared")
    void contributingCountsInstitutionsNotRecords() {
        NetworkService.Network before = seenBy(operatorA);

        declare(operatorA, "CD-" + UUID.randomUUID(), "300.00");
        declare(operatorA, "CD-" + UUID.randomUUID(), "400.00");
        declare(operatorA, "CD-" + UUID.randomUUID(), "500.00");

        NetworkService.Network after = seenBy(operatorA);

        assertThat(after.contributing() - before.contributing())
                .as("three records from one operator is one institution")
                .isEqualTo(1);
        assertThat(after.declaredToday() - before.declaredToday())
                .as("and three declarations")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a sector nobody has recorded is not counted as an industry")
    void sectorsCountWhatWasRecordedRatherThanWhatIsMissing() {
        NetworkService.Network before = seenBy(operatorA);

        // The declaration path sets no sector — it arrives from a mapped import column, and
        // nothing here maps one.
        declare(operatorA, "CD-" + UUID.randomUUID(), "800.00");

        assertThat(seenBy(operatorA).sectors() - before.sectors())
                .as("count(distinct sector) must skip nulls rather than treating 'unknown' as an "
                        + "industry — the screen says 'not recorded' and a 1 here would make it lie")
                .isZero();
    }

    @Test
    @DisplayName("every figure is non-negative, including on an empty network")
    void nothingUnderflows() {
        NetworkService.Network summary = seenBy(operatorA);

        // Guards the aggregate row falling back to zeroes rather than to nulls: a native count
        // over no rows returns a row, but the fallback path in NetworkService.asLong exists for
        // the driver that hands back something unexpected, and a negative or a crash here would
        // mean it did.
        assertThat(Arrays.stream(NetworkService.Network.class.getRecordComponents())
                .map(component -> read(component, summary)))
                .allSatisfy(value -> assertThat(value).isNotNegative());
    }

    private static long read(RecordComponent component, NetworkService.Network summary) {
        try {
            return (long) component.getAccessor().invoke(summary);
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError("could not read " + component.getName(), unreachable);
        }
    }
}
