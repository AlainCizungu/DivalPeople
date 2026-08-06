package ai.dival.dip.modules.employees;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import ai.dival.dip.modules.users.UserAccount;
import ai.dival.dip.modules.users.UserAccountService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * Raises a notification when an employment contract is approaching its end date.
 *
 * <p>Runs across every tenant, one at a time. A scheduled job has no request and therefore no
 * tenant, so each tenant is bound explicitly before its work begins — and because connections are
 * bound to a tenant at checkout, that binding must happen outside the transaction.
 *
 * <p>Each contract is alerted once. Re-notifying every morning until somebody acts trains people
 * to ignore the feed, which costs more than the alert is worth.
 */
@Component
public class ContractExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(ContractExpiryScanner.class);

    /** Roles that should hear about an expiring contract. */
    private static final List<String> NOTIFIED_ROLES = List.of("HR_ADMIN", "TENANT_ADMIN");

    private final TenantService tenants;
    private final EmploymentContractRepository contracts;
    private final UserAccountService users;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;
    private final int noticeDays;

    public ContractExpiryScanner(TenantService tenants, EmploymentContractRepository contracts,
                                 UserAccountService users, NotificationService notifications,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${dip.hr.contract-expiry-notice-days:30}") int noticeDays) {
        this.tenants = tenants;
        this.contracts = contracts;
        this.users = users;
        this.notifications = notifications;
        this.transactionTemplate = transactionTemplate;
        this.noticeDays = noticeDays;
    }

    /** Early morning, before the working day, so alerts are waiting rather than interrupting. */
    @Scheduled(cron = "${dip.hr.contract-expiry-cron:0 0 6 * * *}")
    public void scan() {
        LocalDate cutoff = LocalDate.now().plusDays(noticeDays);
        int raised = 0;

        for (Tenant tenant : tenants.list()) {
            if (!tenant.isActive()) {
                continue;
            }
            try {
                raised += scanTenant(tenant.getId(), cutoff);
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop every other tenant's alerts.
                log.error("Contract expiry scan failed for tenant {}", tenant.getId(), ex);
            }
        }

        if (raised > 0) {
            log.info("Contract expiry scan raised {} notifications", raised);
        }
    }

    private int scanTenant(UUID tenantId, LocalDate cutoff) {
        return TenantContext.runAsResult(tenantId, () ->
                transactionTemplate.execute(status -> {
                    List<EmploymentContract> expiring =
                            contracts.findExpiringWithoutAlert(tenantId, cutoff);
                    if (expiring.isEmpty()) {
                        return 0;
                    }

                    List<UUID> recipients = recipientsFor(tenantId);
                    if (recipients.isEmpty()) {
                        // Nobody to tell. Leave the alert unsent so it fires once somebody can
                        // act on it, rather than marking it done into the void.
                        log.warn("Tenant {} has {} expiring contracts but no HR or tenant admin",
                                tenantId, expiring.size());
                        return 0;
                    }

                    for (EmploymentContract contract : expiring) {
                        notifications.notifyAll(
                                recipients,
                                "contractExpiring",
                                Map.of(
                                        "employee", contract.getEmployee().displayName(),
                                        "days", String.valueOf(daysUntil(contract.getEndDate()))),
                                Notification.Severity.WARNING,
                                "EmploymentContract",
                                contract.getId().toString());
                        contract.markExpiryNotified();
                    }
                    return expiring.size();
                }));
    }

    private long daysUntil(LocalDate endDate) {
        return Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), endDate));
    }

    private List<UUID> recipientsFor(UUID tenantId) {
        return NOTIFIED_ROLES.stream()
                .flatMap(role -> users.findByRole(tenantId, role).stream())
                .map(UserAccount::getId)
                .distinct()
                .toList();
    }
}
