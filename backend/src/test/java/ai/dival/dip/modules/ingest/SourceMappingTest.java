package ai.dival.dip.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What an operator says its columns mean, and why it is never rewritten.
 *
 * <p>A published delivery was derived through whichever mapping was current when it ran. Editing
 * one in place would leave that batch untraceable to the rules that produced it — immutable raw
 * rows pointing at a mapping that has silently changed are provenance pointing at nothing. So a
 * mapping is superseded, and the old version stays readable.
 */
@RequiresDocker
class SourceMappingTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private IngestService ingest;

    private UUID operator;
    private UUID sourceId;

    @BeforeEach
    void setUp() {
        operator = tenants.save(new Tenant("Mapping A", "map-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(operator);
        sourceId = ingest.registerSource("SRC-" + UUID.randomUUID(), "Vodacom write-off export",
                SourceKind.SPREADSHEET, null).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SourceMapping define(String identifierColumn, String amountColumn) {
        return ingest.defineMapping(sourceId, identifierColumn, "RCCM", "Bsr", amountColumn,
                "USD", "POSTPAID", "BUSINESS", null);
    }

    @Test
    @DisplayName("the first mapping is version one and is the current one")
    void firstMappingIsCurrent() {
        SourceMapping mapping = define("BPR_0", "Balance");

        assertThat(mapping.getVersionNumber()).isEqualTo(1);
        assertThat(mapping.isCurrent()).isTrue();
        assertThat(ingest.currentMapping(sourceId)).contains(mapping);
    }

    @Test
    @DisplayName("defining a second supersedes the first rather than editing it")
    void redefiningSupersedes() {
        SourceMapping first = define("BPR_0", "Balance");
        SourceMapping second = define("BPR_0", "360 + days");

        assertThat(second.getVersionNumber()).isEqualTo(2);
        assertThat(ingest.currentMapping(sourceId)).contains(second);

        // The old one is still readable, and still says what it said. A batch published under it
        // can be explained without anybody reconstructing what the rules used to be.
        assertThat(ingest.mappingHistory(sourceId)).hasSize(2);
        assertThat(first.getAmountColumn()).isEqualTo("Balance");
        assertThat(first.isCurrent()).isFalse();
        assertThat(first.getSupersededAt()).isNotNull();
    }

    @Test
    @DisplayName("history is newest first, so the current rules are the first thing read")
    void historyIsNewestFirst() {
        define("BPR_0", "Balance");
        define("BPR_0", "360 + days");
        define("Write off Mars 2025", "Balance");

        assertThat(ingest.mappingHistory(sourceId))
                .extracting(SourceMapping::getVersionNumber)
                .containsExactly(3, 2, 1);
    }

    @Test
    @DisplayName("one column cannot be the identifier and the amount at once")
    void columnsMustBeDistinct() {
        // A mapping like this derives records that look entirely plausible and are nonsense.
        assertThatThrownBy(() -> define("Balance", "Balance"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("three different columns");
    }

    @Test
    @DisplayName("a mapping needs every column it claims to have")
    void blanksAreRefused() {
        assertThatThrownBy(() -> define("  ", "Balance"))
                .isInstanceOf(PolicyRefusedException.class);
        assertThatThrownBy(() -> ingest.defineMapping(sourceId, "BPR_0", "RCCM", "Bsr", "Balance",
                "USD", "  ", "BUSINESS", null))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("a source nobody has mapped yet has no mapping, rather than an empty one")
    void unmappedSourceHasNothing() {
        // The distinction matters at derivation: "not mapped yet" is a thing to tell the operator
        // to go and do, and an empty mapping is a bug that would derive nothing and explain
        // nothing.
        assertThat(ingest.currentMapping(sourceId)).isEmpty();
        assertThat(ingest.mappingHistory(sourceId)).isEmpty();
    }

    @Test
    @DisplayName("one operator cannot see another's mapping")
    void mappingsAreTenantScoped() {
        define("BPR_0", "Balance");

        UUID other = tenants.save(new Tenant("Mapping B", "map-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();

        // Scoped by the source, which is itself tenant-owned — so this is really asserting that
        // asking about somebody else's source finds nothing rather than their rules.
        assertThat(TenantContext.runAsResult(other, () -> ingest.currentMapping(sourceId)))
                .isEmpty();
    }

    @Test
    @DisplayName("a cell the delivery does not have is named, not silently blank")
    void missingColumnIsNamed() {
        SourceMapping mapping = define("BPR_0", "Balance");
        Map<String, String> row = Map.of("Bsr", "Grand Horizon SARL", "Balance", "184000.50");

        // A mapping written against last quarter's header, applied to a file where somebody
        // renamed a column, would otherwise derive thousands of records with a blank identifier —
        // all refused later, for a reason that says nothing about the cause.
        assertThatThrownBy(() -> mapping.cell(row, "BPR_0"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("BPR_0");

        assertThat(mapping.cell(row, "Balance")).isEqualTo("184000.50");
    }

    @Test
    @DisplayName("currency is stored upper-case whatever was typed")
    void currencyIsNormalised() {
        SourceMapping mapping = ingest.defineMapping(sourceId, "BPR_0", "RCCM", "Bsr", "Balance",
                "usd", "POSTPAID", "BUSINESS", null);

        // The reporting threshold is keyed on the currency code. "usd" finding no configured
        // floor would refuse every row in the delivery for a reason nobody could see.
        assertThat(mapping.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("only one mapping per source is ever current")
    void onlyOneCurrentMapping() {
        define("BPR_0", "Balance");
        define("BPR_0", "360 + days");
        define("Write off Mars 2025", "Balance");

        List<SourceMapping> current = ingest.mappingHistory(sourceId).stream()
                .filter(SourceMapping::isCurrent)
                .toList();

        // The partial unique index in V25 is the real guarantee; this asserts the application
        // agrees with it rather than relying on a constraint violation to find out.
        assertThat(current).hasSize(1);
        assertThat(current.get(0).getVersionNumber()).isEqualTo(3);
    }
}
