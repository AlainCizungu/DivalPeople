package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mandatory cross-tenant isolation test.
 *
 * <p>Per AGENTS.md a module is not done until it proves tenant A cannot reach tenant B's rows.
 * These tests are the executable form of that rule for TIX.
 */
@Transactional
@RequiresDocker
class TenantIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private DebtRecordRepository debtRecords;
    @Autowired
    private DebtRecordService debtRecordService;

    private UUID operatorA;
    private UUID operatorB;
    private Subject sharedSubject;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Operator A", "operator-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Operator B", "operator-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();

        Subject subject = new Subject(Subject.SubjectType.INDIVIDUAL, "Jean Kabila",
                LocalDate.of(1990, 5, 12), "CD");
        subject.addIdentifier(new SubjectIdentifier(IdentifierType.NATIONAL_ID, "CD-1234-5678", null));
        sharedSubject = subjects.save(subject);
    }

    @Test
    @DisplayName("an operator sees only its own debt records")
    void operatorSeesOnlyOwnRecords() {
        TenantContext.runAs(operatorA, () ->
                debtRecordService.declare(newDeclaration(), UUID.randomUUID()));
        TenantContext.runAs(operatorB, () ->
                debtRecordService.declare(newDeclaration(), UUID.randomUUID()));

        List<DebtRecord> ownedByA = debtRecords.findByTenantId(operatorA);
        List<DebtRecord> ownedByB = debtRecords.findByTenantId(operatorB);

        assertThat(ownedByA).hasSize(1);
        assertThat(ownedByB).hasSize(1);
        assertThat(ownedByA.get(0).getId()).isNotEqualTo(ownedByB.get(0).getId());
        assertThat(ownedByA.get(0).getTenantId()).isEqualTo(operatorA);
    }

    @Test
    @DisplayName("an operator cannot settle another operator's record")
    void cannotSettleForeignRecord() {
        UUID recordOfA = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(newDeclaration(), UUID.randomUUID())
                        .record().getId());

        assertThatThrownBy(() ->
                TenantContext.runAs(operatorB, () -> debtRecordService.settle(recordOfA, UUID.randomUUID())))
                .isInstanceOf(DebtRecordService.DebtRecordNotFoundException.class);

        // And the record is untouched.
        assertThat(debtRecords.findByIdAndTenantId(recordOfA, operatorA))
                .get()
                .extracting(DebtRecord::getStatus)
                .isEqualTo(DebtStatus.OUTSTANDING);
    }

    @Test
    @DisplayName("a tenant-scoped lookup by id refuses to cross tenants")
    void lookupByIdIsTenantScoped() {
        UUID recordOfA = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(newDeclaration(), UUID.randomUUID())
                        .record().getId());

        assertThat(debtRecords.findByIdAndTenantId(recordOfA, operatorB)).isEmpty();
        assertThat(debtRecords.findByIdAndTenantId(recordOfA, operatorA)).isPresent();
    }

    @Test
    @DisplayName("a tenant-owned entity cannot be persisted without a tenant context")
    void refusesToPersistWithoutTenant() {
        TenantContext.clear();

        Throwable thrown = catchThrowable(() -> debtRecords.saveAndFlush(newRecord(sharedSubject)));

        // The JPA provider is free to wrap an exception thrown from @PrePersist, so assert on the
        // root cause rather than on whatever wrapper happens to surface.
        assertThat(thrown).isNotNull();
        assertThat(rootCauseOf(thrown)).isInstanceOf(TenantContext.TenantContextMissingException.class);
    }

    private static Throwable rootCauseOf(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Goes through the real declaration path rather than constructing the entity.
     *
     * <p>These used to build a DebtRecord directly and hand it to the service, which stopped
     * being possible when declaration grew a threshold and subject resolution — and that is an
     * improvement, not an inconvenience. A test that assembles the entity itself proves isolation
     * for a path no operator can reach. The identifier below resolves to the subject created in
     * setUp, so both operators land on the same person, which is the case that matters: subjects
     * are shared across the exchange and only the records about them are tenant-owned.
     */
    private DeclarationRequest newDeclaration() {
        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, "CD-1234-5678")),
                "Jean Kabila",
                Subject.SubjectType.INDIVIDUAL,
                LocalDate.of(1990, 5, 12),
                "CD",
                new BigDecimal("150.00"),
                "USD",
                "POSTPAID",
                LocalDate.now().minusDays(60),
                true);
    }

    /** Still built by hand: this one must reach the repository without passing through a tenant. */
    private DebtRecord newRecord(Subject subject) {
        return new DebtRecord(subject, new BigDecimal("150.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(60), true);
    }
}
