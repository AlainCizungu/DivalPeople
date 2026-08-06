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
 * Raises notifications for things that are about to expire: employment contracts, and documents
 * such as work permits, visas and professional certifications.
 *
 * <p>Runs across every tenant, one at a time. A scheduled job has no request and therefore no
 * tenant, so each tenant is bound explicitly before its work begins — and because connections are
 * bound to a tenant at checkout, that binding must happen outside the transaction.
 *
 * <p>Each contract is alerted once. Re-notifying every morning until somebody acts trains people
 * to ignore the feed, which costs more than the alert is worth.
 */
@Component
public class HrExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(HrExpiryScanner.class);

    /** Roles that should hear about an expiring contract. */
    private static final List<String> NOTIFIED_ROLES = List.of("HR_ADMIN", "TENANT_ADMIN");

    private final TenantService tenants;
    private final EmploymentContractRepository contracts;
    private final EmployeeDocumentRepository documents;
    private final UserAccountService users;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;
    private final int noticeDays;
    /** Shorter than the contract window: a probation decision has to land before it ends. */
    private final int probationNoticeDays;

    public HrExpiryScanner(TenantService tenants, EmploymentContractRepository contracts,
                           EmployeeDocumentRepository documents,
                           UserAccountService users, NotificationService notifications,
                           TransactionTemplate transactionTemplate,
                           @Value("${dip.hr.contract-expiry-notice-days:30}") int noticeDays,
                           @Value("${dip.hr.probation-notice-days:14}") int probationNoticeDays) {
        this.tenants = tenants;
        this.contracts = contracts;
        this.documents = documents;
        this.users = users;
        this.notifications = notifications;
        this.transactionTemplate = transactionTemplate;
        this.noticeDays = noticeDays;
        this.probationNoticeDays = probationNoticeDays;
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
                raised += scanContracts(tenant.getId(), cutoff);
                raised += scanDocuments(tenant.getId(), cutoff);
                raised += scanProbation(tenant.getId(),
                        LocalDate.now().plusDays(probationNoticeDays));
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop every other tenant's alerts.
                log.error("Expiry scan failed for tenant {}", tenant.getId(), ex);
            }
        }

        if (raised > 0) {
            log.info("Expiry scan raised {} notifications", raised);
        }
    }

    private int scanContracts(UUID tenantId, LocalDate cutoff) {
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

    /**
     * Documents nearing expiry. A lapsed work permit is a compliance problem rather than an
     * administrative one, so it earns the same warning as a contract.
     */
    private int scanDocuments(UUID tenantId, LocalDate cutoff) {
        return TenantContext.runAsResult(tenantId, () ->
                transactionTemplate.execute(status -> {
                    List<EmployeeDocument> expiring =
                            documents.findExpiringWithoutAlert(tenantId, cutoff);
                    if (expiring.isEmpty()) {
                        return 0;
                    }

                    List<UUID> recipients = recipientsFor(tenantId);
                    if (recipients.isEmpty()) {
                        log.warn("Tenant {} has {} expiring documents but no HR or tenant admin",
                                tenantId, expiring.size());
                        return 0;
                    }

                    for (EmployeeDocument document : expiring) {
                        notifications.notifyAll(
                                recipients,
                                "documentExpiring",
                                Map.of(
                                        "employee", document.getEmployee().displayName(),
                                        "document", document.getTitle(),
                                        "days", String.valueOf(daysUntil(document.getExpiresOn()))),
                                // Losing the right to employ somebody outranks a contract renewal.
                                document.getDocumentType().expiryMatters()
                                        ? Notification.Severity.CRITICAL
                                        : Notification.Severity.WARNING,
                                "EmployeeDocument",
                                document.getId().toString());
                        document.markExpiryNotified();
                    }
                    return expiring.size();
                }));
    }

    /**
     * Probation periods ending with nobody having decided.
     *
     * <p>CRITICAL rather than WARNING, and deliberately so. A contract expiring without action
     * leaves a gap somebody notices; a probation expiring without action silently confirms the
     * employee, and the chance to decide is gone.
     */
    private int scanProbation(UUID tenantId, LocalDate cutoff) {
        return TenantContext.runAsResult(tenantId, () ->
                transactionTemplate.execute(status -> {
                    List<EmploymentContract> ending =
                            contracts.findProbationEndingWithoutAlert(tenantId, cutoff);
                    if (ending.isEmpty()) {
                        return 0;
                    }

                    List<UUID> recipients = recipientsFor(tenantId);
                    if (recipients.isEmpty()) {
                        log.warn("Tenant {} has {} probation periods ending but no HR or "
                                + "tenant admin", tenantId, ending.size());
                        return 0;
                    }

                    for (EmploymentContract contract : ending) {
                        notifications.notifyAll(
                                recipients,
                                "probationEnding",
                                Map.of(
                                        "employee", contract.getEmployee().displayName(),
                                        "days", String.valueOf(
                                                daysUntil(contract.getProbationEndDate()))),
                                Notification.Severity.CRITICAL,
                                "EmploymentContract",
                                contract.getId().toString());
                        contract.markProbationNotified();
                    }
                    return ending.size();
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
