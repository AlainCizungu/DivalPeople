package ai.dival.dip.common.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tix.DebtRecord;
import ai.dival.dip.modules.tix.DebtRecordRepository;
import ai.dival.dip.modules.tix.IdentifierType;
import ai.dival.dip.modules.tix.Subject;
import ai.dival.dip.modules.tix.SubjectIdentifier;
import ai.dival.dip.modules.tix.SubjectIdentifierRepository;
import ai.dival.dip.modules.tix.SubjectRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds one demo subject with an outstanding debt held by operator B.
 *
 * <p>Exists so the exchange path can actually be exercised locally: an inquiry from operator A
 * against this identifier matches, crosses the tenant boundary, and returns OUTSTANDING_DEBT.
 * Without any data every inquiry returns NO_MATCH and returns early, leaving the most
 * security-sensitive code path — the cross-operator read under row-level security — unexercised.
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

    private final SubjectRepository subjects;
    private final SubjectIdentifierRepository identifiers;
    private final DebtRecordRepository debtRecords;
    private final TransactionTemplate transactionTemplate;

    public LocalTixSeeder(SubjectRepository subjects,
                          SubjectIdentifierRepository identifiers,
                          DebtRecordRepository debtRecords,
                          TransactionTemplate transactionTemplate) {
        this.subjects = subjects;
        this.identifiers = identifiers;
        this.debtRecords = debtRecords;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String normalized = SubjectIdentifier.normalizeValue(DEMO_NATIONAL_ID);
        if (identifiers.findByIdentifierTypeAndNormalizedValue(IdentifierType.NATIONAL_ID, normalized)
                .isPresent()) {
            return;
        }

        UUID operatorB = LocalTenantSeeder.OPERATOR_B;

        // The tenant must be bound *before* the transaction starts, because the connection is
        // bound to its tenant at checkout and that is what the row-level security policy reads.
        TenantContext.runAs(operatorB, () -> transactionTemplate.executeWithoutResult(status -> {
            Subject subject = new Subject(
                    Subject.SubjectType.INDIVIDUAL, DEMO_NAME, LocalDate.of(1990, 5, 12), "CD");
            subject.addIdentifier(new SubjectIdentifier(IdentifierType.NATIONAL_ID, DEMO_NATIONAL_ID));
            subject.addIdentifier(new SubjectIdentifier(IdentifierType.MSISDN, "+243900000001"));
            Subject saved = subjects.save(subject);

            debtRecords.save(new DebtRecord(
                    saved, new BigDecimal("250.00"), "USD", "POSTPAID",
                    LocalDate.now().minusDays(90), true));
        }));

        log.info("Seeded demo TIX subject '{}' with an outstanding debt held by operator B",
                DEMO_NAME);
    }
}
