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
 * Search, and the one property that matters more than every other one here.
 *
 * <p>A subject is shared: {@code tix_subject} carries no tenant, because several operators declare
 * against the same person and that is what makes an exchange an exchange. It follows that a search
 * beginning at the subject table would search the national registry, and one participant could
 * type a letter and list every business its competitors had reported. That is not a privacy
 * footnote — it is the reason a second telecom would decline to join.
 *
 * <p>So the first three tests here ask from the wrong side, and they are the point of the file.
 */
@RequiresDocker
class SearchTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SearchService search;

    private UUID operatorA;
    private UUID operatorB;
    private String suffix;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Search A", "s-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Search B", "s-b-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        // Unique per test, so a name search cannot collide with another test's fixtures.
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID declare(UUID operator, String name, String rccm, String amount, long daysOverdue) {
        return TenantContext.runAsResult(operator, () -> debtRecords.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.RCCM, rccm)),
                        name, Subject.SubjectType.BUSINESS, null, "CD",
                        new BigDecimal(amount), "USD", "POSTPAID",
                        LocalDate.now().minusDays(daysOverdue), true),
                null).record().getSubject().getId());
    }

    private List<SearchService.Result> searchAs(UUID operator, String query) {
        return TenantContext.runAsResult(operator, () -> search.searchOwn(query, null));
    }

    // --- the boundary -------------------------------------------------------

    @Test
    @DisplayName("a search never returns a business only another operator has reported")
    void searchIsConfinedToTheCallersOwnBook() {
        declare(operatorB, "Horizon Concurrent " + suffix, "CD/KIN/RCCM/22-B-" + suffix, "500.00", 30);

        // Operator A has declared nothing. The subject exists in the registry, is shared, and
        // must be invisible here.
        assertThat(searchAs(operatorA, "Horizon Concurrent " + suffix)).isEmpty();
        assertThat(searchAs(operatorB, "Horizon Concurrent " + suffix)).hasSize(1);
    }

    @Test
    @DisplayName("nor by its register number, which is the obvious way round the name")
    void searchByIdentifierIsConfinedToo() {
        String rccm = "CD/KIN/RCCM/23-B-" + suffix;
        declare(operatorB, "Concurrent SARL " + suffix, rccm, "500.00", 30);

        assertThat(searchAs(operatorA, rccm)).isEmpty();
        assertThat(searchAs(operatorB, rccm)).hasSize(1);
    }

    @Test
    @DisplayName("a profile is refused for a subject the operator holds nothing against")
    void profileIsRefusedForSomebodyElsesSubject() {
        UUID subjectId = declare(operatorB, "Profil Interdit " + suffix,
                "CD/KIN/RCCM/24-B-" + suffix, "500.00", 30);

        // "Not found" rather than "not yours". The two must be indistinguishable, or the endpoint
        // confirms the subject exists and becomes the enumeration tool the search is not.
        assertThatThrownBy(() ->
                TenantContext.runAsResult(operatorA, () -> search.profileOf(subjectId, null)))
                .isInstanceOf(SearchService.SubjectNotHeldException.class);

        assertThatThrownBy(() ->
                TenantContext.runAsResult(operatorA, () -> search.profileOf(UUID.randomUUID(), null)))
                .isInstanceOf(SearchService.SubjectNotHeldException.class);
    }

    // --- finding things -----------------------------------------------------

    @Test
    @DisplayName("a partial name finds the business")
    void partialNameMatches() {
        declare(operatorA, "Grand Horizon SARL " + suffix, "CD/KIN/RCCM/25-B-" + suffix,
                "18400.00", 200);

        assertThat(searchAs(operatorA, "grand horizon"))
                .extracting(SearchService.Result::name)
                .anySatisfy(name -> assertThat(name).startsWith("Grand Horizon SARL"));
    }

    @Test
    @DisplayName("the summary carries what a credit officer is looking at the screen for")
    void resultsSummariseTheExposure() {
        declare(operatorA, "Atlas Résumé " + suffix, "CD/KIN/RCCM/26-B-" + suffix, "9620.00", 288);

        SearchService.Result result = searchAs(operatorA, "atlas résumé").get(0);

        assertThat(result.recordCount()).isEqualTo(1);
        assertThat(result.openCount()).isEqualTo(1);
        assertThat(result.outstanding()).isEqualTo("9620.00");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.oldestBand()).isEqualTo(AgingBand.OVER_270);
    }

    @Test
    @DisplayName("a very short query is refused rather than matching the whole book")
    void shortQueriesAreRefused() {
        assertThatThrownBy(() -> searchAs(operatorA, "a"))
                .isInstanceOf(PolicyRefusedException.class);
        assertThatThrownBy(() -> searchAs(operatorA, "   "))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("an expired record makes the subject unfindable, including by its own operator")
    void erasureAppliesToYouToo() {
        declare(operatorA, "Périmé SARL " + suffix, "CD/KIN/RCCM/27-B-" + suffix, "500.00", 30);
        assertThat(searchAs(operatorA, "périmé")).hasSize(1);

        // The retention clock is five years for a first default. Nothing purges here — the
        // query excludes expired records on its own, because the difference between erasure and
        // concealment is that erasure applies to the operator that declared it as well.
        //
        // Asserted by moving the record rather than the clock: declaring it far enough in the
        // past that its retention has already run out.
        String old = "CD/KIN/RCCM/28-B-" + suffix;
        declare(operatorA, "Ancien SARL " + suffix, old, "500.00", 365 * 6);
        assertThat(searchAs(operatorA, "ancien")).isEmpty();
    }

    // --- the profile --------------------------------------------------------

    @Test
    @DisplayName("a profile shows every record this operator holds, aged and with its identifiers")
    void profileShowsTheOperatorsOwnFile() {
        String rccm = "CD/KIN/RCCM/29-B-" + suffix;
        UUID subjectId = declare(operatorA, "Profil Complet " + suffix, rccm, "4310.50", 205);

        SearchService.Profile profile =
                TenantContext.runAsResult(operatorA, () -> search.profileOf(subjectId, null));

        assertThat(profile.name()).startsWith("Profil Complet");
        assertThat(profile.subjectType()).isEqualTo(Subject.SubjectType.BUSINESS);
        assertThat(profile.identifiers()).extracting(SearchService.Identifier::type)
                .contains(IdentifierType.RCCM);
        assertThat(profile.records()).singleElement().satisfies(held -> {
            assertThat(held.amount()).isEqualTo("4310.50");
            assertThat(held.band()).isEqualTo(AgingBand.DAYS_270);
            assertThat(held.imported()).isFalse();
        });
    }

    @Test
    @DisplayName("accents do not decide whether a business can be found")
    void accentsAreNormalisedOnBothSides() {
        declare(operatorA, "Société Générale d'Alimentation " + suffix,
                "CD/KIN/RCCM/30-B-" + suffix, "1200.00", 60);

        // Real exports are inconsistent about accents and a credit officer types what is on the
        // paper in front of them. Both sides go through the same normalisation, so either
        // spelling finds it — and if that ever stops being true, the profile screen shows the
        // normalised form, which is where the difference becomes visible.
        assertThat(searchAs(operatorA, "societe generale")).hasSize(1);
        assertThat(searchAs(operatorA, "Société Générale")).hasSize(1);
    }
}
