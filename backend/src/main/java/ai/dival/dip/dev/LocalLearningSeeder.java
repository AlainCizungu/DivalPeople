package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import ai.dival.dip.modules.learning.Course;
import ai.dival.dip.modules.learning.CourseEnrolment;
import ai.dival.dip.modules.learning.CourseRepository;
import ai.dival.dip.modules.learning.DeliveryMode;
import ai.dival.dip.modules.learning.LearningService;
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
 * Seeds a course catalogue and training records that produce a real compliance gap.
 *
 * <p>The point of the data is the gap, not the catalogue. One person holds a current safety
 * ticket, one holds a lapsed one, and one never sat the course — so the compliance report shows
 * the distinction the module exists to make, rather than a wall of green.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-learning", havingValue = "true")
@Order(24) // after performance, before TIX
public class LocalLearningSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalLearningSeeder.class);

    private final LearningService learning;
    private final CourseRepository courses;
    private final EmployeeRepository employees;
    private final TransactionTemplate transactionTemplate;

    public LocalLearningSeeder(LearningService learning, CourseRepository courses,
                               EmployeeRepository employees,
                               TransactionTemplate transactionTemplate) {
        this.learning = learning;
        this.courses = courses;
        this.employees = employees;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!courses.findByTenantIdOrderByTitleAsc(tenantId).isEmpty()) {
                return;
            }

            Course safety = learning.createCourse("TOWER-SAFETY", "Tower climbing safety",
                    DeliveryMode.CLASSROOM,
                    "Working at height, rescue procedure and equipment inspection.",
                    "National Safety Board", 480, true, 24, 70, null);

            Course dataProtection = learning.createCourse("DATA-PROT",
                    "Subject data and privacy", DeliveryMode.ONLINE,
                    "What may be held about a subscriber, and for how long.",
                    null, 90, true, 12, null, null);

            learning.createCourse("SQL-101", "Introduction to SQL", DeliveryMode.ONLINE,
                    "Querying the reporting warehouse.", null, 240, false, null, null, null);

            Employee director = employee(tenantId, "EMP-001");
            Employee engineer = employee(tenantId, "EMP-002");
            Employee analyst = employee(tenantId, "EMP-003");
            if (director == null || engineer == null) {
                log.info("Seeded 3 courses; no employees to enrol");
                return;
            }

            LocalDate today = LocalDate.now();

            // Current: passed eight months ago, valid for another sixteen.
            pass(engineer, safety, today.minusMonths(8), 88);

            // Lapsed: passed over two years ago, so the certificate has expired. This is the row
            // that makes the compliance report worth reading.
            pass(director, safety, today.minusMonths(27), 91);

            // Expiring soon: inside the sixty-day notice window, so the scan has something to
            // warn about on its next run.
            pass(engineer, dataProtection, today.minusMonths(12).plusDays(20), null);

            // A failure that stays on the record, followed by a fresh attempt in progress.
            if (analyst != null) {
                CourseEnrolment failed = learning.enrol(analyst.getId(), safety.getId(),
                        today.minusMonths(4), null);
                learning.complete(failed.getId(), today.minusMonths(4), 55,
                        "Did not pass the rescue module", null);

                CourseEnrolment retry = learning.enrol(analyst.getId(), safety.getId(),
                        today.minusDays(10), null);
                learning.start(retry.getId(), null);
            }

            // Sweep, so anything already past its date reads as expired rather than valid.
            int expired = learning.expireLapsed(today);

            log.info("Seeded 3 courses and 5 training records for operator A; {} already lapsed",
                    expired);
        }));
    }

    private void pass(Employee employee, Course course, LocalDate completedOn, Integer score) {
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(),
                completedOn.minusDays(7), null);
        learning.complete(enrolment.getId(), completedOn, score, null, null);
    }

    private Employee employee(UUID tenantId, String number) {
        return employees.findByTenantIdAndEmployeeNumber(tenantId, number).orElse(null);
    }
}
