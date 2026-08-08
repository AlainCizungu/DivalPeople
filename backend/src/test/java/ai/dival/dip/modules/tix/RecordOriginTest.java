package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.RecordOrigin;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every derived record says where it came from, and cannot lie about it.
 *
 * <p>Rule 5 is "preserve data provenance". The interesting half is not that an imported record
 * names its source row — it is that no record can exist claiming to be imported without naming
 * one. A nullable foreign key alone would have permitted "imported, source unknown" as a silent
 * third state, and silent third states are how a lineage requirement becomes a lineage
 * aspiration.
 *
 * <p>Not {@code @Transactional}, and raw SQL for the constraint tests: a check constraint fires at
 * the database, so the only way to find out whether it is really there is to try to violate it.
 */
@RequiresDocker
class RecordOriginTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID operator;

    @BeforeEach
    void setUp() {
        operator = tenants.save(new Tenant("Origin", "origin-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(operator);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a record declared through the API says so")
    void apiDeclarationsCarryTheirOrigin() {
        DebtRecord record = debtRecords.declare(declaration(), null).record();

        assertThat(record.getOrigin()).isEqualTo(RecordOrigin.API_DECLARATION);
        assertThat(record.getRawRecordId())
                .as("an API declaration has no source row, and does not pretend to")
                .isNull();
    }

    @Test
    @DisplayName("nothing can claim to be imported without naming the row it came from")
    void importedRecordsMustNameASourceRow() {
        UUID id = debtRecords.declare(declaration(), null).record().getId();

        // Straight SQL, because this is a database constraint and the entity has no way to
        // express the illegal state. If this UPDATE succeeds, the lineage requirement is
        // decoration: a record could sit in the registry marked IMPORT with nothing behind it.
        assertThatThrownBy(() ->
                jdbc.update("UPDATE tix_debt_record SET origin = 'IMPORT' WHERE id = ?", id))
                .as("the check constraint ties origin to raw_record_id")
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT origin FROM tix_debt_record WHERE id = ?", String.class, id))
                .isEqualTo("API_DECLARATION");
    }

    @Test
    @DisplayName("an origin outside the vocabulary is refused")
    void unknownOriginsAreRefused() {
        UUID id = debtRecords.declare(declaration(), null).record().getId();

        assertThatThrownBy(() ->
                jdbc.update("UPDATE tix_debt_record SET origin = 'GUESSED' WHERE id = ?", id))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("records that predate the provenance column were backfilled honestly")
    void existingRecordsAreMarkedAsDeclarations() {
        // V20 backfilled every existing row to API_DECLARATION, which is what they were: the
        // declaration endpoint was the only door into the registry before this migration. Marking
        // them IMPORT would have invented a lineage that never existed.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tix_debt_record WHERE origin IS NULL", Integer.class))
                .isZero();
    }

    private static DeclarationRequest declaration() {
        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, "CD-" + UUID.randomUUID())),
                "Jean Kabila", Subject.SubjectType.INDIVIDUAL, LocalDate.of(1990, 5, 12), "CD",
                new BigDecimal("150.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true);
    }
}
