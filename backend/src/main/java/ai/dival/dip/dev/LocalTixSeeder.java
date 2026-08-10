package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
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
 * Seeds one demo subject with an outstanding debt held by operator B.
 *
 * <p>Exists so the exchange path can actually be exercised locally: an inquiry from operator A
 * against this identifier matches, crosses the tenant boundary, and returns OUTSTANDING_DEBT.
 * Without any data every inquiry returns NO_MATCH and returns early, leaving the most
 * security-sensitive code path — the cross-operator read under row-level security — unexercised.
 *
 * <p>Goes through {@link DebtRecordService#declare} rather than assembling entities and saving
 * them. It used to do the latter, which meant the demo data arrived by a route no operator can
 * use: it skipped the reporting threshold, skipped subject resolution, and — once retention
 * existed — skipped setting the column that says when the record must be erased. Seed data that
 * takes a different path from real data is seed data that proves nothing, and it is how a NOT NULL
 * column added in a migration turns into an application that will not start.
 *
 * <p>Local profile only. Never runs in a deployed environment.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-tix", havingValue = "true")
@Order(20) // after LocalTenantSeeder; the tenant must exist first
public class LocalTixSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalTixSeeder.class);

    /** Matches the identifier used by infra/dev.sh check. */
    public static final String DEMO_NATIONAL_ID = "CD-1234-5678";
    private static final String DEMO_NAME = "Jean Kabila";

    private final SubjectIdentifierRepository identifiers;
    private final DebtRecordService debtRecords;

    public LocalTixSeeder(SubjectIdentifierRepository identifiers, DebtRecordService debtRecords) {
        this.identifiers = identifiers;
        this.debtRecords = debtRecords;
    }

    @Override
    public void run(ApplicationArguments args) {
        String normalized = SubjectIdentifier.normalizeValue(DEMO_NATIONAL_ID);
        if (identifiers.findByIdentifierTypeAndNormalizedValueAndOwnerTenantIdIsNull(IdentifierType.NATIONAL_ID, normalized)
                .isPresent()) {
            return;
        }

        UUID operatorB = LocalTenantSeeder.OPERATOR_B;

        // The tenant must be bound *before* the transaction starts, because the connection is
        // bound to its tenant at checkout and that is what the row-level security policy reads.
        // declare() is @Transactional, so binding the tenant around the call is enough — the
        // explicit TransactionTemplate this used to need went with the hand-built entities.
        TenantContext.runAs(operatorB, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.NATIONAL_ID, DEMO_NATIONAL_ID),
                        new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.MSISDN, "+243900000001")),
                DEMO_NAME,
                Subject.SubjectType.INDIVIDUAL,
                LocalDate.of(1990, 5, 12),
                "CD",
                // Comfortably over the 100 USD reporting threshold, so the demo data is not
                // quietly dependent on the floor staying where it is.
                new BigDecimal("250.00"),
                "USD",
                "POSTPAID",
                LocalDate.now().minusDays(90),
                true), null));

        log.info("Seeded demo TIX subject '{}' with an outstanding debt held by operator B",
                DEMO_NAME);
    }
}
