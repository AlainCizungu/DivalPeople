package ai.dival.dip.modules.lifecycle;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import ai.dival.dip.modules.users.UserAccount;
import ai.dival.dip.modules.users.UserAccountService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chases checklist steps that are past their date.
 *
 * <p>Separate from the HR expiry scan rather than folded into it, because reaching into another
 * module's repository is the boundary this codebase does not cross — and because these alerts go
 * to a different audience. An overdue step belongs to whoever owns it, not to HR at large.
 *
 * <p>Each item is chased once. Re-notifying every morning until somebody acts trains people to
 * ignore the feed, which costs more than the alert is worth.
 */
@Component
public class ChecklistReminderScanner {

    private static final Logger log = LoggerFactory.getLogger(ChecklistReminderScanner.class);

    /** Who hears about an overdue step when it has no assignee, or the assignee has no login. */
    private static final List<String> FALLBACK_ROLES = List.of("HR_ADMIN", "TENANT_ADMIN");

    private final TenantService tenants;
    private final ChecklistItemRepository items;
    private final UserAccountService users;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;

    public ChecklistReminderScanner(TenantService tenants, ChecklistItemRepository items,
                                    UserAccountService users, NotificationService notifications,
                                    TransactionTemplate transactionTemplate) {
        this.tenants = tenants;
        this.items = items;
        this.users = users;
        this.notifications = notifications;
        this.transactionTemplate = transactionTemplate;
    }

    /** Just after the HR scan, so the morning's alerts arrive together. */
    @Scheduled(cron = "${dip.hr.checklist-overdue-cron:0 15 6 * * *}")
    public void scan() {
        LocalDate today = LocalDate.now();
        int raised = 0;

        for (Tenant tenant : tenants.list()) {
            if (!tenant.isActive()) {
                continue;
            }
            try {
                raised += scanTenant(tenant.getId(), today);
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop every other tenant's reminders.
                log.error("Checklist reminder scan failed for tenant {}", tenant.getId(), ex);
            }
        }

        if (raised > 0) {
            log.info("Checklist scan raised {} reminders", raised);
        }
    }

    private int scanTenant(UUID tenantId, LocalDate today) {
        return TenantContext.runAsResult(tenantId, () ->
                transactionTemplate.execute(status -> {
                    List<ChecklistItem> overdue = items.findOverdueWithoutAlert(tenantId, today);
                    if (overdue.isEmpty()) {
                        return 0;
                    }

                    List<UUID> fallback = fallbackRecipients(tenantId);
                    int raised = 0;

                    for (ChecklistItem item : overdue) {
                        List<UUID> recipients = recipientsFor(tenantId, item, fallback);
                        if (recipients.isEmpty()) {
                            // Nobody to tell. Leave it unalerted so it fires once somebody can
                            // act on it, rather than marking it chased into the void.
                            continue;
                        }

                        notifications.notifyAll(
                                recipients,
                                "checklistItemOverdue",
                                Map.of(
                                        "task", item.getTitle(),
                                        "employee",
                                        item.getChecklist().getEmployee().displayName(),
                                        "days", String.valueOf(
                                                today.toEpochDay() - item.getDueOn().toEpochDay())),
                                // A missed offboarding step is a former employee who can still
                                // get in; a missed onboarding step is an awkward first week.
                                item.getChecklist().getChecklistType() == ChecklistType.OFFBOARDING
                                        && item.isMandatory()
                                        ? Notification.Severity.CRITICAL
                                        : Notification.Severity.WARNING,
                                "ChecklistItem",
                                item.getId().toString());
                        item.markOverdueNotified();
                        raised++;
                    }
                    return raised;
                }));
    }

    /**
     * The assignee first, because an alert addressed to everybody is addressed to nobody. HR is
     * copied only when the step is mandatory, or when there is no assignee to chase.
     */
    private List<UUID> recipientsFor(UUID tenantId, ChecklistItem item, List<UUID> fallback) {
        List<UUID> recipients = new ArrayList<>();

        Employee assignee = item.getAssignee();
        if (assignee != null && assignee.getUserAccountId() != null
                && users.findById(tenantId, assignee.getUserAccountId()).isPresent()) {
            recipients.add(assignee.getUserAccountId());
        }

        if (recipients.isEmpty() || item.isMandatory()) {
            fallback.stream().filter(id -> !recipients.contains(id)).forEach(recipients::add);
        }
        return recipients;
    }

    private List<UUID> fallbackRecipients(UUID tenantId) {
        return FALLBACK_ROLES.stream()
                .flatMap(role -> users.findByRole(tenantId, role).stream())
                .map(UserAccount::getId)
                .distinct()
                .toList();
    }
}
