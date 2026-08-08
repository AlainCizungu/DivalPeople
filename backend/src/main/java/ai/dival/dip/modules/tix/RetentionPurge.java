package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes what the retention period says must no longer exist.
 *
 * <p>Hiding expired records from inquiries is not erasure. It is the same personal data with a
 * predicate in front of it, and it would not survive the first question from a data protection
 * authority about what the platform holds. The terms of reference say <em>effacement</em>. This is
 * the part that does it.
 *
 * <p><strong>Per tenant, never across.</strong> The row-level security policy on
 * {@code tix_debt_record} reads {@code USING (tenant_id = app_current_tenant() OR
 * app_exchange_mode())}, and a USING clause governs DELETE as well as SELECT. A purge running in
 * exchange mode could therefore delete another operator's records — the fastest implementation
 * here is also the one that hands a cross-tenant delete to anything that later reuses the flag.
 * So this binds each tenant in turn and erases inside that tenant's own boundary. It is slower
 * and it keeps the boundary a boundary.
 *
 * <p>Subjects are erased too, once nothing refers to them. A subject exists only to carry debt
 * records; deleting every record about a person while keeping their name, date of birth and
 * national ID number would leave personal data with no lawful basis and nothing left to explain
 * why it is held. That cleanup runs outside the per-tenant loop because subjects are shared and
 * carry no tenant of their own.
 */
@Component
public class RetentionPurge {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurge.class);

    private final TenantService tenants;
    private final DebtRecordRepository debtRecords;
    private final SubjectRepository subjects;
    private final AuditService audit;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final Clock clock;

    public RetentionPurge(TenantService tenants, DebtRecordRepository debtRecords,
                          SubjectRepository subjects, AuditService audit,
                          TransactionTemplate transactionTemplate, EntityManager entityManager,
                          Clock clock) {
        this.tenants = tenants;
        this.debtRecords = debtRecords;
        this.subjects = subjects;
        this.audit = audit;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Nightly, well away from the HR sweeps so a slow purge cannot delay the morning alerts. */
    @Scheduled(cron = "${dip.tix.retention-purge-cron:0 15 2 * * *}")
    public void purge() {
        purgeAsOf(LocalDate.now(clock));
    }

    /**
     * Separated from the schedule so a test can ask what happens in four years without waiting.
     *
     * @return how many debt records were erased
     */
    public int purgeAsOf(LocalDate today) {
        int erased = 0;

        for (Tenant tenant : tenants.list()) {
            try {
                erased += purgeTenant(tenant.getId(), today);
            } catch (RuntimeException ex) {
                // One operator's data problem must not stop every other operator's erasure. A
                // purge that aborts halfway leaves the platform holding data past its period, and
                // the failure is invisible unless it is logged loudly here.
                log.error("Retention purge failed for tenant {}", tenant.getId(), ex);
            }
        }

        int orphaned = purgeOrphanedSubjects();

        if (erased > 0 || orphaned > 0) {
            log.info("Retention purge erased {} debt records and {} subjects", erased, orphaned);
        }
        return erased;
    }

    private int purgeTenant(UUID tenantId, LocalDate today) {
        Integer erased = TenantContext.runAsResult(tenantId, () ->
                transactionTemplate.execute(status -> {
                    List<DebtRecord> expired =
                            debtRecords.findByTenantIdAndRetentionUntilBefore(tenantId, today);
                    if (expired.isEmpty()) {
                        return 0;
                    }

                    for (DebtRecord record : expired) {
                        // Audited before deletion, and the audit row deliberately carries the
                        // record's id and nothing about the person. The trail must be able to
                        // show that erasure happened — an erasure nobody can evidence is
                        // indistinguishable from data loss — without the audit log becoming the
                        // place the erased data lives on.
                        audit.record("TIX_RECORD_ERASED", "DebtRecord",
                                record.getId().toString(), AuditService.OUTCOME_SUCCESS, null,
                                "Retention period ended " + record.getRetentionUntil());
                    }

                    debtRecords.deleteAll(expired);
                    return expired.size();
                }));
        return erased == null ? 0 : erased;
    }

    /**
     * Removes subjects nothing refers to any more.
     *
     * <p>Outside the tenant loop and in its own transaction: a subject is shared, so it can only
     * be judged orphaned once every tenant has finished erasing. Doing this inside the loop would
     * ask "does anybody still hold a record about this person" while other operators' deletions
     * were still pending, and answer no too early — erasing somebody who is still legitimately
     * listed elsewhere.
     */
    private int purgeOrphanedSubjects() {
        Integer removed = transactionTemplate.execute(status -> {
            // Exchange mode, and this is the one place in the purge that needs it.
            //
            // "Does anybody still hold a record about this person" is a question about every
            // operator, and tix_debt_record is under row-level security. Asked from inside one
            // tenant, the answer counts only that tenant's records and almost every subject looks
            // orphaned; asked with no tenant bound at all, app_current_tenant() is null, every
            // policy comparison is false, and *every* subject looks orphaned. Either way this
            // deletes people who are still legitimately listed — quietly, nightly.
            //
            // The flag also relaxes DELETE, because USING governs it. That is tolerable here and
            // nowhere else in this class: the only deletion in this transaction is from
            // tix_subject, which is deliberately shared and carries no policy at all. The
            // per-tenant loop above, which does delete debt records, never turns it on.
            entityManager.createNativeQuery("SELECT set_config('app.exchange', 'on', true)")
                    .getSingleResult();

            List<Subject> orphans = subjects.findWithNoDebtRecords();
            if (orphans.isEmpty()) {
                return 0;
            }
            // Identifiers cascade: the mapping is orphanRemoval with CascadeType.ALL, and V19
            // grants DELETE on the identifier table so the cascade can actually run.
            subjects.deleteAll(orphans);
            return orphans.size();
        });
        return removed == null ? 0 : removed;
    }
}
