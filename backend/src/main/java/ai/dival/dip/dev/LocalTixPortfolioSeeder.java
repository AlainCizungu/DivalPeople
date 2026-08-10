package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.TenantService;
import ai.dival.dip.modules.tix.DebtRecordService;
import ai.dival.dip.modules.tix.DeclarationRequest;
import ai.dival.dip.modules.tix.IdentifierType;
import ai.dival.dip.modules.tix.Subject;
import ai.dival.dip.modules.tix.SubjectIdentifier;
import ai.dival.dip.modules.tix.SubjectIdentifierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A book with a shape.
 *
 * <p>{@link LocalTixSeeder} puts one subject in the registry, which is enough to prove the
 * cross-operator read works and not enough to look at. The exposure view aged by band, the credit
 * check reporting two institutions, the status breakdown — all of them are technically correct and
 * visually empty against a single record.
 *
 * <p>So: businesses rather than individuals, because the credit check is about businesses;
 * default dates spread across every aging band, because a distribution with one bar in it teaches
 * nothing; two currencies, because never summing them is the point and one currency cannot show
 * it; and one business owing both operators, because that is the case the exchange exists for.
 *
 * <p>Everything goes through {@link DebtRecordService#declare}, {@code settle} and {@code dispute}
 * — the same path an operator uses. Seed data that arrives by a route no operator can take is seed
 * data that proves nothing, and it is how a NOT NULL column added in a migration turns into an
 * application that will not start.
 *
 * <p>Local profile only. Never runs in a deployed environment.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-tix", havingValue = "true")
@Order(21) // after LocalTixSeeder, which owns the identifier infra/dev.sh checks for
public class LocalTixPortfolioSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalTixPortfolioSeeder.class);

    /** Seeding is skipped when this exists, so a restart is a no-op. */
    private static final String MARKER_RCCM = "CD/KIN/RCCM/14-B-4001";

    /**
     * A business, and what it owes.
     *
     * @param daysOverdue how long ago the obligation fell due; chosen per row so that every aging
     *                    band has something in it
     */
    private record Debtor(String name, String rccm, String taxNumber, String amount,
                          String currency, String service, long daysOverdue) {
    }

    /**
     * Operator A's book. Amounts clear the 100 USD floor comfortably, so the demo does not
     * quietly depend on the threshold staying where it is.
     */
    private static final List<Debtor> OPERATOR_A_BOOK = List.of(
            new Debtor("Grand Horizon SARL", "CD/KIN/RCCM/14-B-4001", "A1740025X",
                    "18400.00", "USD", "POSTPAID", 412),
            new Debtor("Atlas Distribution SARL", "CD/KIN/RCCM/15-B-2288", "A1740119M",
                    "9620.00", "USD", "POSTPAID", 288),
            new Debtor("Kin Logistique SARL", "CD/KIN/RCCM/16-B-0917", "A1740233K",
                    "4310.50", "USD", "LEASED-LINE", 205),
            new Debtor("Congo Agro-Industrie SARL", "CD/LSH/RCCM/17-B-1140", "A1740377T",
                    "2870.00", "USD", "POSTPAID", 164),
            new Debtor("Établissements Mwamba", "CD/KIN/RCCM/18-B-3302", "A1740401B",
                    "1250.75", "USD", "DATA", 133),
            new Debtor("Lualaba Transport SARL", "CD/LUB/RCCM/19-B-0455", "A1740588P",
                    "980.00", "USD", "POSTPAID", 96),
            new Debtor("Boutique Nzuzi", "CD/KIN/RCCM/20-B-7781", "A1740612R",
                    "412.30", "USD", "DATA", 54),
            new Debtor("Kivu Télécom Services", "CD/GOM/RCCM/21-B-1023", "A1740744C",
                    "260.00", "USD", "POSTPAID", 21),
            // The second currency. Never added to the first, and a book with one currency cannot
            // demonstrate that.
            new Debtor("Société Minière du Katanga", "CD/LUB/RCCM/13-B-0088", "A1740890D",
                    "48750000.00", "CDF", "LEASED-LINE", 331));

    /** Operator B's book, plus the one business that owes both. */
    private static final List<Debtor> OPERATOR_B_BOOK = List.of(
            new Debtor("Entreprise Générale Tshikala", "CD/KIN/RCCM/15-B-6604", "A1741002F",
                    "7150.00", "USD", "POSTPAID", 377),
            new Debtor("Matadi Port Services SARL", "CD/MAT/RCCM/17-B-2219", "A1741188G",
                    "3400.00", "USD", "LEASED-LINE", 243),
            new Debtor("Pharmacie Bomoko", "CD/KIN/RCCM/19-B-9014", "A1741250H",
                    "1180.00", "USD", "DATA", 112),
            new Debtor("Bandundu Distribution", "CD/BDD/RCCM/20-B-3376", "A1741399J",
                    "640.00", "USD", "POSTPAID", 67),
            new Debtor("Ateliers Kasai", "CD/KAN/RCCM/21-B-0502", "A1741470L",
                    "305.00", "USD", "DATA", 12));

    /**
     * Owed at both operators, which is the only case the exchange exists for.
     *
     * <p>Looking this business up returns OUTSTANDING_DEBT with two institutions reporting, and
     * neither of their names — which is the property the boundary section of the landing page
     * claims and the thing worth demonstrating live.
     */
    private static final Debtor SHARED = new Debtor(
            "Trans-Congo Négoce SARL", "CD/KIN/RCCM/16-B-5150", "A1741555N",
            "5900.00", "USD", "POSTPAID", 198);

    private final SubjectIdentifierRepository identifiers;
    private final DebtRecordService debtRecords;
    private final TenantService tenants;

    public LocalTixPortfolioSeeder(SubjectIdentifierRepository identifiers,
                                   DebtRecordService debtRecords,
                                   TenantService tenants) {
        this.identifiers = identifiers;
        this.debtRecords = debtRecords;
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (identifiers.findByIdentifierTypeAndNormalizedValueAndOwnerTenantIdIsNull(
                IdentifierType.RCCM, SubjectIdentifier.normalizeValue(MARKER_RCCM)).isPresent()) {
            return;
        }

        UUID operatorA = LocalTenantSeeder.OPERATOR_A;
        UUID operatorB = LocalTenantSeeder.OPERATOR_B;

        OPERATOR_A_BOOK.forEach(debtor -> declare(operatorA, debtor));
        OPERATOR_B_BOOK.forEach(debtor -> declare(operatorB, debtor));

        // The same business, declared by both. Two separate declarations resolving to one subject
        // through its RCCM — which is entity resolution doing its job, not a fixture.
        declare(operatorA, SHARED);
        declare(operatorB, new Debtor(SHARED.name(), SHARED.rccm(), SHARED.taxNumber(),
                "2250.00", SHARED.currency(), "DATA", 141));

        // A recovered debt and a contested one, so the status breakdown and the contested column
        // are not permanently zero. Both go through the real endpoints.
        settleOne(operatorA);
        disputeOne(operatorB);

        // One participant suspended, so the participants screen shows both states.
        tenants.deactivate(LocalTenantSeeder.suspendedParticipant(), null);

        log.info("Seeded {} businesses across two operators, spread across every aging band",
                OPERATOR_A_BOOK.size() + OPERATOR_B_BOOK.size() + 1);
    }

    private UUID declare(UUID operator, Debtor debtor) {
        return TenantContext.runAsResult(operator, () -> debtRecords.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                        IdentifierType.RCCM, debtor.rccm()),
                                new DeclarationRequest.SubmittedIdentifier(
                                        IdentifierType.TAX_NUMBER, debtor.taxNumber())),
                        debtor.name(),
                        Subject.SubjectType.BUSINESS,
                        null,
                        "CD",
                        new BigDecimal(debtor.amount()),
                        debtor.currency(),
                        debtor.service(),
                        LocalDate.now().minusDays(debtor.daysOverdue()),
                        true),
                null).record().getId());
    }

    /**
     * Settles one of operator A's records.
     *
     * <p>Declared here rather than reusing one of the rows above, because settling a listed row
     * would take it out of the aging distribution the list was built to fill. A recovered debt
     * needs its own business.
     */
    private void settleOne(UUID operator) {
        UUID recordId = declare(operator, new Debtor(
                "Goma Construction SARL", "CD/GOM/RCCM/18-B-4417", "A1741688Q",
                "3120.00", "USD", "POSTPAID", 260));
        TenantContext.runAs(operator, () -> debtRecords.settle(recordId, null));
    }

    private void disputeOne(UUID operator) {
        UUID recordId = declare(operator, new Debtor(
                "Uvira Commerce Général", "CD/UVI/RCCM/20-B-1199", "A1741712S",
                "1475.00", "USD", "DATA", 178));
        TenantContext.runAs(operator, () -> debtRecords.dispute(recordId, null));
    }
}
