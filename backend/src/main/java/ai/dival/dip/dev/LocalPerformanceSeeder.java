package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import ai.dival.dip.modules.performance.FeedbackRelationship;
import ai.dival.dip.modules.performance.Goal;
import ai.dival.dip.modules.performance.GoalStatus;
import ai.dival.dip.modules.performance.PerformanceReview;
import ai.dival.dip.modules.performance.PerformanceService;
import ai.dival.dip.modules.performance.Rating;
import ai.dival.dip.modules.performance.ReviewCycle;
import ai.dival.dip.modules.performance.ReviewCycleRepository;
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
 * Seeds a review cycle with reviews at different stages.
 *
 * <p>Deliberately uneven: one review waiting on both sides, one where only the employee has
 * written, one shared and acknowledged with a disagreement recorded. A board where every review
 * is at the same stage demonstrates nothing about the rules that govern moving between them.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-performance", havingValue = "true")
@Order(23) // after attendance, before TIX
public class LocalPerformanceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalPerformanceSeeder.class);

    private final PerformanceService performance;
    private final ReviewCycleRepository cycles;
    private final EmployeeRepository employees;
    private final TransactionTemplate transactionTemplate;

    public LocalPerformanceSeeder(PerformanceService performance, ReviewCycleRepository cycles,
                                  EmployeeRepository employees,
                                  TransactionTemplate transactionTemplate) {
        this.performance = performance;
        this.cycles = cycles;
        this.employees = employees;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!cycles.findByTenantIdOrderByPeriodStartDesc(tenantId).isEmpty()) {
                return;
            }

            Employee director = employee(tenantId, "EMP-001");
            Employee engineer = employee(tenantId, "EMP-002");
            Employee analyst = employee(tenantId, "EMP-003");
            if (director == null || engineer == null) {
                log.info("No employees to seed performance for");
                return;
            }

            int year = LocalDate.now().getYear();
            ReviewCycle cycle = performance.createCycle(year + " annual review",
                    LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                    LocalDate.of(year + 1, 1, 31), null);
            performance.openCycle(cycle.getId(), null);

            // A cascade: the engineer's goal contributes to the director's.
            Goal uptime = performance.createGoal(director.getId(),
                    "Raise network uptime to 99.5%", "Across the Kinshasa tower estate",
                    "Monthly uptime report", new BigDecimal("3"),
                    LocalDate.of(year, 12, 31), cycle.getId(), null, null);
            performance.activateGoal(uptime.getId(), null);
            performance.recordProgress(uptime.getId(), 60, null);

            Goal repairTime = performance.createGoal(engineer.getId(),
                    "Cut mean repair time to four hours", null,
                    "From the maintenance log", new BigDecimal("2"),
                    LocalDate.of(year, 9, 30), cycle.getId(), uptime.getId(), null);
            performance.activateGoal(repairTime.getId(), null);
            performance.recordProgress(repairTime.getId(), 45, null);

            // One goal closed short of the target, with the reason recorded.
            Goal spares = performance.createGoal(engineer.getId(),
                    "Stock every van with a full spares kit", null, null, null,
                    LocalDate.of(year, 6, 30), cycle.getId(), null, null);
            performance.activateGoal(spares.getId(), null);
            performance.closeGoal(spares.getId(), GoalStatus.PARTIALLY_MET,
                    "Four vans of six; the supplier could not deliver the rest in time", null);

            // The engineer's review: only they have written, so neither side can read the other.
            PerformanceReview inProgress = performance.openReview(cycle.getId(),
                    engineer.getId(), director.getId(), null);
            performance.saveSelfAssessment(inProgress.getId(),
                    "Cut repair time from six hours to four and a half. The spares programme "
                            + "stalled on supply rather than on the plan.", null);
            performance.submitSelfAssessment(inProgress.getId(), null);

            // The analyst's review: finished, calibrated, shared, and disagreed with — the case
            // people most want to see working before they trust the module.
            if (analyst != null) {
                PerformanceReview settled = performance.openReview(cycle.getId(),
                        analyst.getId(), director.getId(), null);
                performance.saveSelfAssessment(settled.getId(),
                        "Closed the quarterly reporting backlog and trained two colleagues on it.",
                        null);
                performance.submitSelfAssessment(settled.getId(), null);
                performance.saveReviewerAssessment(settled.getId(),
                        "Reliable and well organised. The reporting backlog was cleared without "
                                + "anybody having to chase it.", Rating.EXCEEDS, null);
                performance.submitReviewerAssessment(settled.getId(), null);

                performance.addFeedback(settled.getId(), engineer.getId(),
                        FeedbackRelationship.PEER,
                        "Explains the numbers rather than just sending them.", false, null);

                performance.calibrate(settled.getId(), Rating.MEETS,
                        "Moderated against the rest of the finance cohort", null);
                performance.share(settled.getId(), null);
                performance.acknowledge(settled.getId(),
                        "I have read this. I do not accept the moderation from Exceeds to Meets "
                                + "and would like it reviewed.", true, null);
            }

            log.info("Seeded a review cycle, 3 goals and 2 reviews for operator A");
        }));
    }

    private Employee employee(UUID tenantId, String number) {
        return employees.findByTenantIdAndEmployeeNumber(tenantId, number).orElse(null);
    }
}
