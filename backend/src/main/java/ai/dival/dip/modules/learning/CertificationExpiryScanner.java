package ai.dival.dip.modules.learning;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import ai.dival.dip.modules.users.UserAccount;
import ai.dival.dip.modules.users.UserAccountService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Warns before a qualification lapses, and marks it once it has.
 *
 * <p>Both halves matter and they are not the same job. The warning gives somebody time to book a
 * refresher; the sweep is what makes the compliance report tell the truth the morning after,
 * whether or not anybody acted on the warning.
 *
 * <p>The person holding the certificate is told as well as HR. A safety ticket is theirs to keep
 * current, and an alert that only reaches an administrator is one they find out about from a
 * supervisor turning them away at a site gate.
 */
@Component
public class CertificationExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(CertificationExpiryScanner.class);

    private static final List<String> NOTIFIED_ROLES = List.of("HR_ADMIN", "TENANT_ADMIN");

    private final TenantService tenants;
    private final LearningService learning;
    private final CourseEnrolmentRepository enrolments;
    private final UserAccountService users;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;
    private final int noticeDays;

    public CertificationExpiryScanner(TenantService tenants, LearningService learning,
                                      CourseEnrolmentRepository enrolments,
                                      UserAccountService users,
                                      NotificationService notifications,
                                      TransactionTemplate transactionTemplate,
                                      @Value("${dip.hr.certification-notice-days:60}")
                                      int noticeDays) {
        this.tenants = tenants;
        this.learning = learning;
        this.enrolments = enrolments;
        this.users = users;
        this.notifications = notifications;
        this.transactionTemplate = transactionTemplate;
        this.noticeDays = noticeDays;
    }

    /** Early, alongside the other HR sweeps, so the morning's alerts arrive together. */
    @Scheduled(cron = "${dip.hr.certification-expiry-cron:0 30 6 * * *}")
    public void scan() {
        scanAsOf(LocalDate.now());
    }

    /** Separated from the schedule so a test can ask for any date without waiting a year. */
    public int scanAsOf(LocalDate today) {
        int raised = 0;

        for (Tenant tenant : tenants.list()) {
            if (!tenant.isActive()) {
                continue;
            }
            try {
                raised += scanTenant(tenant.getId(), today);
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop every other tenant's alerts.
                log.error("Certification scan failed for tenant {}", tenant.getId(), ex);
            }
        }

        if (raised > 0) {
            log.info("Certification scan raised {} notifications", raised);
        }
        return raised;
    }

    private int scanTenant(UUID tenantId, LocalDate today) {
        return TenantContext.runAsResult(tenantId, () ->
                transactionTemplate.execute(status -> {
                    // Mark what has already lapsed first, so a warning is never sent about a
                    // certificate that expired yesterday.
                    learning.expireLapsed(today);

                    List<CourseEnrolment> expiring = enrolments.findExpiringWithoutAlert(
                            tenantId, today.plusDays(noticeDays));
                    if (expiring.isEmpty()) {
                        return 0;
                    }

                    List<UUID> administrators = administrators(tenantId);
                    int raised = 0;

                    for (CourseEnrolment enrolment : expiring) {
                        List<UUID> recipients = recipientsFor(enrolment, administrators);
                        if (recipients.isEmpty()) {
                            // Nobody to tell. Leave it unalerted so it fires once somebody can
                            // act, rather than marking it chased into the void.
                            continue;
                        }

                        notifications.notifyAll(
                                recipients,
                                "certificationExpiring",
                                Map.of(
                                        "employee", enrolment.getEmployee().displayName(),
                                        "course", enrolment.getCourse().getTitle(),
                                        "days", String.valueOf(
                                                daysUntil(today, enrolment.getExpiresOn()))),
                                // A lapsed safety ticket stops somebody working, so it outranks
                                // an ordinary reminder.
                                enrolment.getCourse().isMandatory()
                                        ? Notification.Severity.CRITICAL
                                        : Notification.Severity.WARNING,
                                "CourseEnrolment",
                                enrolment.getId().toString());
                        enrolment.markExpiryNotified();
                        raised++;
                    }
                    return raised;
                }));
    }

    /** The holder first, then HR. It is their ticket to keep current. */
    private List<UUID> recipientsFor(CourseEnrolment enrolment, List<UUID> administrators) {
        List<UUID> recipients = new ArrayList<>();

        Employee holder = enrolment.getEmployee();
        if (holder.getUserAccountId() != null) {
            recipients.add(holder.getUserAccountId());
        }
        administrators.stream().filter(id -> !recipients.contains(id)).forEach(recipients::add);
        return recipients;
    }

    private List<UUID> administrators(UUID tenantId) {
        return NOTIFIED_ROLES.stream()
                .flatMap(role -> users.findByRole(tenantId, role).stream())
                .map(UserAccount::getId)
                .distinct()
                .toList();
    }

    private long daysUntil(LocalDate today, LocalDate expiry) {
        return Math.max(0, ChronoUnit.DAYS.between(today, expiry));
    }
}
