package ai.dival.dip.modules.overview;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.BatchStatus;
import ai.dival.dip.modules.ingest.ImportBatchRepository;
import ai.dival.dip.modules.tix.DebtRecordRepository;
import ai.dival.dip.modules.tix.DebtStatus;
import ai.dival.dip.modules.tix.SubjectRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is waiting on somebody, counted where the data is.
 *
 * <p>The front door used to fetch every debt record this operator had ever declared, send them all
 * to the browser, and count them there. That was written when a busy tenant held a dozen records
 * and its own javadoc said the fix was an endpoint that counts. One real import made it 3,699
 * records on every page load, so this is that endpoint.
 *
 * <p><strong>Organised by what needs a person, not by what is interesting.</strong> A dashboard
 * that leads with a total is a dashboard nobody opens twice. The figures that earn the space are
 * the ones with somebody waiting at the other end: a rights case past its statutory deadline, a
 * delivery somebody uploaded and abandoned, a record about to be erased.
 *
 * <p>Every count here has a screen behind it that lists exactly what was counted. That is a
 * constraint rather than a convenience — a number on a dashboard that cannot be opened is a number
 * nobody can check, and this platform's whole argument is that its figures can be checked.
 *
 * <p>Lives in its own module rather than in {@code tix}, because the platform is what has a front
 * door. TIX is one thing an operator does here; the deliveries, the rights queue and the audit
 * trail are not telecom concepts and should not be reached through a telecom package.
 */
@Service
public class OverviewService {

    /**
     * How far ahead the "soon" figures look.
     *
     * <p>Seven days for a rights deadline and ninety for retention, and the gap between them is
     * the point. A case due next week needs somebody this week; a record expiring in three months
     * needs planning, not action. One window for both would make one of them useless.
     */
    private static final int RIGHTS_HORIZON_DAYS = 7;
    private static final int RETENTION_HORIZON_DAYS = 90;

    private final DebtRecordRepository debtRecords;
    private final SubjectRequestRepository requests;
    private final ImportBatchRepository batches;
    private final Clock clock;

    public OverviewService(DebtRecordRepository debtRecords, SubjectRequestRepository requests,
                           ImportBatchRepository batches, Clock clock) {
        this.debtRecords = debtRecords;
        this.requests = requests;
        this.batches = batches;
        this.clock = clock;
    }

    /**
     * @param canDeclare  whether the caller may see the register figures
     * @param canSeeCases whether the caller may see the rights queue
     */
    @Transactional(readOnly = true)
    public Overview forCaller(boolean canDeclare, boolean canSeeCases) {
        UUID tenantId = TenantContext.require();
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        // Null rather than zero when the caller may not see a section, and the difference is not
        // pedantic. Nought overdue cases is a fact somebody can act on; nought because you are not
        // allowed to know is a different statement, and showing the first when you mean the second
        // tells the reader something false in the most reassuring possible direction.
        Register register = canDeclare ? new Register(
                debtRecords.countByTenantId(tenantId),
                debtRecords.countByTenantIdAndStatus(tenantId, DebtStatus.OUTSTANDING),
                debtRecords.countByTenantIdAndStatus(tenantId, DebtStatus.DISPUTED),
                debtRecords.countByTenantIdAndStatus(tenantId, DebtStatus.SETTLED),
                debtRecords.countByTenantIdAndRetentionUntilBetween(
                        tenantId, today, today.plusDays(RETENTION_HORIZON_DAYS)),
                // Past its retention date and still here. Should be nought every morning; if it
                // is not, the nightly purge has stopped and nothing else in the product says so.
                debtRecords.countByTenantIdAndRetentionUntilBetween(
                        tenantId, LocalDate.EPOCH, today.minusDays(1)))
                : null;

        Rights rights = canSeeCases ? new Rights(
                requests.countOpen(tenantId),
                requests.countOverdue(tenantId, now),
                requests.countDueBefore(tenantId, now,
                        now.plus(RIGHTS_HORIZON_DAYS, ChronoUnit.DAYS)))
                : null;

        Deliveries deliveries = canDeclare ? new Deliveries(
                batches.countByTenantIdAndStatus(tenantId, BatchStatus.RECEIVED),
                batches.countByTenantIdAndStatus(tenantId, BatchStatus.VALIDATED),
                batches.countByTenantIdAndStatus(tenantId, BatchStatus.PUBLISHED))
                : null;

        return new Overview(today, register, rights, deliveries);
    }

    /**
     * @param asOf        the date the figures were counted on, so a stale tab is obvious
     * @param register    null when the caller may not see the operator's own records
     * @param rights      null when the caller may not see the rights queue
     * @param deliveries  null when the caller may not see imports
     */
    public record Overview(LocalDate asOf, Register register, Rights rights,
                           Deliveries deliveries) {
    }

    /**
     * @param expiringSoon   inside the retention horizon and still live
     * @param awaitingErasure past retention and not yet purged. Nought every morning, or the
     *                        nightly sweep has stopped
     */
    public record Register(long total, long outstanding, long contested, long settled,
                           long expiringSoon, long awaitingErasure) {
    }

    /** @param overdue past a statutory deadline, which is itself grounds for a complaint */
    public record Rights(long open, long overdue, long dueSoon) {
    }

    /**
     * @param awaitingValidation received and not yet looked at
     * @param awaitingPublication validated and not yet accepted — somebody started and stopped
     * @param published          accepted, and possibly not yet turned into records
     */
    public record Deliveries(long awaitingValidation, long awaitingPublication, long published) {
    }
}
