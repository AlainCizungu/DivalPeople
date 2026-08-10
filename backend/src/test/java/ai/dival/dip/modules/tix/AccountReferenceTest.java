package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.annotation.Transactional;

/**
 * The one identifier that must not resolve across the exchange.
 *
 * <p>Every other kind of identifier in this system exists to join two operators' records into one
 * subject. An account reference exists because the real files have nothing else, and it has to be
 * prevented from doing that join — telecoms number their customers from one upwards, so the same
 * account number exists at all of them and means a different company at each.
 *
 * <p>The failure this guards against is the quiet kind. Nothing errors: the second operator's
 * declaration lands on the first operator's subject, inherits its debts, and every screen keeps
 * working. A company would be refused credit because an unrelated company at a different telecom
 * shares its customer number.
 */
@Transactional
@RequiresDocker
class AccountReferenceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SubjectIdentifierRepository identifiers;

    private UUID vodacom;
    private UUID airtel;

    @BeforeEach
    void setUp() {
        vodacom = tenants.save(new Tenant("Telecom A", "tel-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        airtel = tenants.save(new Tenant("Telecom B", "tel-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(vodacom);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the same account number at two operators is two different companies")
    void accountReferencesDoNotCollideAcrossOperators() {
        // Taken from the real export's BPR_0 column, which is exactly the shape of value that
        // would previously have had to be declared as an RCCM registration.
        String account = "V0172109";

        UUID first = debtRecords.declare(onAccount(account, "Grand Horizon SARL"), null)
                .record().getSubject().getId();

        DebtRecordService.Declaration second = TenantContext.runAsResult(airtel, () ->
                debtRecords.declare(onAccount(account, "Kin Logistique SARL"), null));

        assertThat(second.record().getSubject().getId())
                .as("two operators' customer number 0172109 are not the same company")
                .isNotEqualTo(first);
        assertThat(second.subjectWasCreated())
                .as("the second operator is opening a file, not adding to one")
                .isTrue();
    }

    @Test
    @DisplayName("a national document still joins two operators onto one company")
    void nationalIdentifiersStillResolveAcrossTheExchange() {
        // The contrast that makes the test above meaningful. If scoping had been applied to every
        // identifier rather than to the one that needs it, this would fail and the exchange would
        // have quietly stopped being an exchange.
        String rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);

        UUID first = debtRecords.declare(
                declaration(IdentifierType.RCCM, rccm, "Grand Horizon SARL"), null)
                .record().getSubject().getId();

        UUID second = TenantContext.runAsResult(airtel, () -> debtRecords.declare(
                declaration(IdentifierType.RCCM, rccm, "Grand Horizon SARL"), null)
                .record().getSubject().getId());

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("one operator's account number does not resolve for another")
    void anotherOperatorsAccountNumberFindsNothing() {
        String account = "V0185670";
        debtRecords.declare(onAccount(account, "Secretariat General"), null);

        // Not "finds a different subject" — finds nothing at all. An operator asking about a
        // number it did not issue is asking a question with no answer, and the honest reply is
        // silence rather than somebody else's customer.
        assertThat(TenantContext.runAsResult(airtel, () -> identifiers.locate(
                IdentifierType.ACCOUNT_REFERENCE,
                SubjectIdentifier.normalizeValue(account), airtel)))
                .isEmpty();
    }

    @Test
    @DisplayName("the same operator declaring its own account number twice adds to one file")
    void anOperatorResolvesItsOwnAccountNumber() {
        String account = "V0190001";
        UUID first = debtRecords.declare(onAccount(account, "Kin Logistique SARL"), null)
                .record().getSubject().getId();

        DebtRecordService.Declaration again =
                debtRecords.declare(onAccount(account, "Kin Logistique SARL"), null);

        assertThat(again.record().getSubject().getId()).isEqualTo(first);
        assertThat(again.subjectWasCreated()).isFalse();
    }

    @Test
    @DisplayName("the stored identifier carries the operator that issued it")
    void theIssuingOperatorIsRecorded() {
        String account = "V0199999";
        debtRecords.declare(onAccount(account, "Grand Horizon SARL"), null);

        assertThat(identifiers.locate(IdentifierType.ACCOUNT_REFERENCE,
                SubjectIdentifier.normalizeValue(account), vodacom))
                .get()
                .extracting(SubjectIdentifier::getOwnerTenantId)
                .isEqualTo(vodacom);
    }

    // --- the constructor refuses both halves of the mistake ------------------

    @Test
    @DisplayName("an account reference without an operator is refused")
    void anAccountReferenceNeedsAnIssuer() {
        // This is the row that would sit in the national namespace and collide with every other
        // operator's numbering. The database says the same thing in a CHECK constraint.
        assertThatThrownBy(() ->
                new SubjectIdentifier(IdentifierType.ACCOUNT_REFERENCE, "V0172109", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACCOUNT_REFERENCE");
    }

    @Test
    @DisplayName("a national document scoped to one operator is refused")
    void aNationalDocumentBelongsToNobody() {
        // The opposite mistake, and the one that fails silently rather than loudly: an RCCM buried
        // inside one operator is invisible to every other, so the exchange returns "no adverse
        // record" for a company three operators are reporting.
        assertThatThrownBy(() ->
                new SubjectIdentifier(IdentifierType.RCCM, "CD/KIN/RCCM/22-B-8800", vodacom))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RCCM");
    }

    private static DeclarationRequest onAccount(String account, String name) {
        return declaration(IdentifierType.ACCOUNT_REFERENCE, account, name);
    }

    private static DeclarationRequest declaration(
            IdentifierType type, String value, String name) {
        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(type, value)),
                name, Subject.SubjectType.BUSINESS, null, "CD",
                new BigDecimal("150.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(60), true);
    }
}
