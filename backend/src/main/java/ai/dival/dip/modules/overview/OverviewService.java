package ai.dival.dip.modules.overview;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.IngestService;
import ai.dival.dip.modules.tenants.TenantService;
import ai.dival.dip.modules.tix.DebtRecordService;
import ai.dival.dip.modules.tix.NetworkService;
import ai.dival.dip.modules.tix.SubjectRightsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 *
 * <p><strong>It counts nothing itself.</strong> The first version of this class reached straight
 * into three other modules' repositories and wrote the queries here, which the architecture check
 * had been catching all along on a run nobody had done. Each module now answers for its own
 * figures and this one decides who may see them and over what horizon — which is the only thing a
 * front door should know.
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

    private final DebtRecordService debtRecords;
    private final SubjectRightsService rights;
    private final IngestService ingest;
    private final NetworkService network;
    private final TenantService tenants;
    private final Clock clock;

    public OverviewService(DebtRecordService debtRecords, SubjectRightsService rights,
                           IngestService ingest, NetworkService network, TenantService tenants,
                           Clock clock) {
        this.debtRecords = debtRecords;
        this.rights = rights;
        this.ingest = ingest;
        this.network = network;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * @param canDeclare  whether the caller may see the register figures
     * @param canSeeCases whether the caller may see the rights queue
     */
    @Transactional(readOnly = true)
    public Overview forCaller(boolean canDeclare, boolean canSeeCases) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        // Null rather than zero when the caller may not see a section, and the difference is not
        // pedantic. Nought overdue cases is a fact somebody can act on; nought because you are not
        // allowed to know is a different statement, and showing the first when you mean the second
        // tells the reader something false in the most reassuring possible direction.
        //
        // The short-circuit matters as much as the null: a section the caller cannot see is never
        // queried, so the refusal costs nothing and leaves no trace of the question.
        return new Overview(
                today,
                // Not gated on a role. It is the name of the organisation the caller is signed
                // into, which they already know; withholding it would only make the header worse
                // for somebody with narrow permissions.
                tenants.nameOf(TenantContext.require()).orElse(null),
                canDeclare
                        ? debtRecords.register(today, today.plusDays(RETENTION_HORIZON_DAYS))
                        : null,
                canSeeCases
                        ? rights.queue(now, now.plus(RIGHTS_HORIZON_DAYS, ChronoUnit.DAYS))
                        : null,
                canDeclare ? ingest.deliverySummary() : null,
                // Also ungated, and that is the decision this section rests on. These are counts
                // of the exchange as a whole, and the argument for showing them to everybody is
                // that none of them can be turned into the identity of a participant — see
                // NetworkService, which is where that is actually enforced.
                network.summarise());
    }

    /**
     * @param asOf         the date the figures were counted on, so a stale tab is obvious
     * @param organisation what this operator calls itself; null if its row has gone
     * @param register     null when the caller may not see the operator's own records
     * @param rights       null when the caller may not see the rights queue
     * @param deliveries   null when the caller may not see imports
     * @param network      the exchange as a whole, and the only section not about the caller
     */
    public record Overview(LocalDate asOf,
                           String organisation,
                           DebtRecordService.RegisterSummary register,
                           SubjectRightsService.RightsQueue rights,
                           IngestService.DeliverySummary deliveries,
                           NetworkService.Network network) {
    }
}
