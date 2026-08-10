package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.IngestService;
import ai.dival.dip.modules.ingest.SourceKind;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A delivery that names its customers and numbers none of them.
 *
 * <p>The Orange export has 342 rows, 342 distinct customer names, and no identifier of any kind:
 * its first column is a row number and its second is the name. Every mapping used to require an
 * identifier column, so that file could not be described at all.
 *
 * <p>Identifying by name is the weakest thing this system does and it is worth being explicit
 * about which risk is guarded and which is not. Two companies of one name arriving in a single
 * delivery are caught here, before anything is written. Two arriving months apart are not caught
 * by anything, and the tests say so rather than implying a completeness that does not exist.
 *
 * <p>Not annotated {@code @Transactional}: the deriver opens a real transaction per row, and a
 * test transaction wrapped around it would test a different thing from what production does.
 */
@RequiresDocker
class IdentityFromNameTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private IngestService ingest;
    @Autowired
    private ImportDeriver deriver;
    @Autowired
    private SubjectIdentifierRepository identifiers;

    private UUID orange;
    private UUID vodacom;
    /**
     * Appended to every customer name.
     *
     * <p>Subjects are shared across the exchange and this class is not transactional, so its
     * fixtures outlive it. A name-identified subject called plainly {@code CENI} would sit in the
     * registry answering other classes' name inquiries.
     */
    private String suffix;

    @BeforeEach
    void setUp() {
        orange = tenants.save(new Tenant("Orange", "orange-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        vodacom = tenants.save(new Tenant("Vodacom", "voda-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantContext.set(orange);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a delivery with no identifier column imports, keyed on the name")
    void aFileWithNoIdentifierImports() {
        UUID batch = deliver(List.of(
                row("DMARK DRC SARL " + suffix, "930925.82"),
                row("CENI " + suffix, "174568.92")));

        ImportDeriver.Report report = deriver.derive(batch, true, null);

        assertThat(report.created()).isEqualTo(2);
        assertThat(report.refused()).isZero();
        assertThat(identifiers.locate(IdentifierType.REPORTED_NAME,
                SubjectIdentifier.normalizeValue("DMARK DRC SARL " + suffix), orange)).isPresent();
    }

    @Test
    @DisplayName("two rows sharing a name refuse the whole delivery, before anything is written")
    void twoRowsWithOneNameRefuseEverything() {
        UUID batch = deliver(List.of(
                row("DMARK DRC SARL " + suffix, "930925.82"),
                row("CENI " + suffix, "174568.92"),
                row("DMARK DRC SARL " + suffix, "120000.00")));

        // Refused whole rather than importing two and reporting one refusal. Two different
        // companies resolving to one subject is worse than importing nothing: the debts of one
        // land on the other, and every screen goes on working.
        assertThatThrownBy(() -> deriver.derive(batch, true, null))
                .isInstanceOf(PolicyRefusedException.class)
                // Rows 1 and 3 of the data, which is how RawRecord numbers them — the first row
                // of data is row 1, so a refusal names a line the operator can go and look at.
                .hasMessageContaining("Rows 1 and 3")
                .hasMessageContaining("DMARK DRC SARL " + suffix);

        assertThat(identifiers.locate(IdentifierType.REPORTED_NAME,
                SubjectIdentifier.normalizeValue("CENI " + suffix), orange))
                .as("the row before the clash was not imported either")
                .isEmpty();
    }

    @Test
    @DisplayName("the same company name at two operators stays two companies")
    void namesDoNotJoinOperators() {
        deriver.derive(deliver(List.of(row("CENI " + suffix, "174568.92"))), true, null);

        UUID vodacomSubject = TenantContext.runAsResult(vodacom, () -> {
            UUID batch = deliver(List.of(row("CENI " + suffix, "301000.00")));
            deriver.derive(batch, true, null);
            return identifiers.locate(IdentifierType.REPORTED_NAME,
                    SubjectIdentifier.normalizeValue("CENI " + suffix), vodacom)
                    .orElseThrow().getSubject().getId();
        });

        UUID orangeSubject = identifiers.locate(IdentifierType.REPORTED_NAME,
                SubjectIdentifier.normalizeValue("CENI " + suffix), orange)
                .orElseThrow().getSubject().getId();

        // Nothing in either file says whether these are one company or two, so the exchange does
        // not decide. Joining them on a name would be the system inventing a fact.
        assertThat(vodacomSubject).isNotEqualTo(orangeSubject);
    }

    @Test
    @DisplayName("one operator's name-identified customer is invisible to another")
    void nameIdentitiesDoNotLeak() {
        deriver.derive(deliver(List.of(row("DMARK DRC SARL " + suffix, "930925.82"))), true, null);

        assertThat(TenantContext.runAsResult(vodacom, () -> identifiers.locate(
                IdentifierType.REPORTED_NAME,
                SubjectIdentifier.normalizeValue("DMARK DRC SARL " + suffix), vodacom)))
                .isEmpty();
    }

    @Test
    @DisplayName("a name is never strong enough to carry a match on its own")
    void aNameIsNotAStrongIdentifier() {
        // The read path sorts strong identifiers first and scores them higher. A name nobody
        // issued must not sit alongside a passport in that ordering.
        assertThat(IdentifierType.REPORTED_NAME.isStrong()).isFalse();
        assertThat(IdentifierType.REPORTED_NAME.isOperatorScoped()).isTrue();
    }

    private static Map<String, String> row(String name, String balance) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("Customer", name);
        cells.put("Balance", balance);
        return cells;
    }

    /** A published delivery whose mapping names no identifier column at all. */
    private UUID deliver(List<Map<String, String>> rows) {
        UUID sourceId = ingest.registerSource("SRC-" + UUID.randomUUID(), "Orange export",
                SourceKind.SPREADSHEET, null).getId();
        // Nulls where an identifier column and type would go. That is the whole feature.
        ingest.defineMapping(sourceId, null, null, "Customer", "Balance",
                "USD", "POSTPAID", "BUSINESS", null);

        UUID batchId = ingest.receive(sourceId, "orange-" + UUID.randomUUID() + ".xlsx",
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
                rows, LocalDate.now().minusDays(1), null).getId();
        ingest.validate(batchId, null);
        ingest.publish(batchId, null);
        return batchId;
    }
}
