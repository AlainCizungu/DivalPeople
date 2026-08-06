package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import ai.dival.dip.modules.lifecycle.ChecklistTemplate;
import ai.dival.dip.modules.lifecycle.ChecklistTemplateRepository;
import ai.dival.dip.modules.lifecycle.ChecklistType;
import ai.dival.dip.modules.lifecycle.ItemCategory;
import ai.dival.dip.modules.lifecycle.ItemStatus;
import ai.dival.dip.modules.lifecycle.EmployeeChecklist;
import ai.dival.dip.modules.lifecycle.LifecycleService;
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
 * Seeds an onboarding and an offboarding template, and one live onboarding list.
 *
 * <p>The live list is deliberately anchored in the recent past with a step already overdue, so
 * the reminder scan has something real to find rather than only a green board.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-lifecycle", havingValue = "true")
@Order(20) // after recruitment, before TIX
public class LocalLifecycleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalLifecycleSeeder.class);

    private final LifecycleService lifecycle;
    private final ChecklistTemplateRepository templates;
    private final EmployeeRepository employees;
    private final TransactionTemplate transactionTemplate;

    public LocalLifecycleSeeder(LifecycleService lifecycle, ChecklistTemplateRepository templates,
                                EmployeeRepository employees,
                                TransactionTemplate transactionTemplate) {
        this.lifecycle = lifecycle;
        this.templates = templates;
        this.employees = employees;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!templates.findByTenantIdOrderByNameAsc(tenantId).isEmpty()) {
                return;
            }

            ChecklistTemplate joining = lifecycle.createTemplate(
                    "ONB-STD", "Standard onboarding", ChecklistType.ONBOARDING, null);
            // Negative offsets: the work that makes a first day go well happens before it.
            addItem(joining, "Send the signed contract", ItemCategory.PAPERWORK,
                    "HR_ADMIN", -10, true);
            addItem(joining, "Register with payroll", ItemCategory.PAYROLL,
                    "PAYROLL_OFFICER", -5, true);
            addItem(joining, "Prepare laptop and SIM", ItemCategory.EQUIPMENT, null, -2, false);
            addItem(joining, "Create accounts and building pass", ItemCategory.ACCESS,
                    null, -1, true);
            addItem(joining, "Introduce to the team", ItemCategory.INTRODUCTION, null, 0, false);
            addItem(joining, "Safety and compliance briefing", ItemCategory.COMPLIANCE,
                    "COMPLIANCE_OFFICER", 3, true);

            ChecklistTemplate leaving = lifecycle.createTemplate(
                    "OFB-STD", "Standard offboarding", ChecklistType.OFFBOARDING, null);
            // Access items are mandatory on the way out. A former employee who can still open the
            // door is the failure this whole list exists to prevent.
            addItem(leaving, "Revoke system accounts", ItemCategory.ACCESS, null, 0, true);
            addItem(leaving, "Collect building pass and keys", ItemCategory.ACCESS, null, 0, true);
            addItem(leaving, "Collect laptop and SIM", ItemCategory.EQUIPMENT, null, 0, true);
            addItem(leaving, "Final payroll settlement", ItemCategory.PAYROLL,
                    "PAYROLL_OFFICER", 5, true);
            addItem(leaving, "Handover notes", ItemCategory.PAPERWORK, null, -3, false);
            addItem(leaving, "Exit interview", ItemCategory.INTRODUCTION, "HR_ADMIN", -1, false);

            Employee analyst = employees
                    .findByTenantIdAndEmployeeNumber(tenantId, "EMP-003")
                    .orElse(null);
            Employee director = employees
                    .findByTenantIdAndEmployeeNumber(tenantId, "EMP-001")
                    .orElse(null);
            if (analyst == null) {
                log.info("Seeded 2 checklist templates; no employees to raise a list for");
                return;
            }

            // Anchored a week ago, so the first steps are already past their date.
            EmployeeChecklist raised = lifecycle.raise(
                    analyst.getId(), joining.getId(), LocalDate.now().minusDays(7),
                    director == null ? null : director.getId(), null);

            // Two done, the rest outstanding: a board where every row looks the same shows
            // nothing about where the work actually is.
            lifecycle.settle(raised.getItems().get(0).getId(), ItemStatus.DONE, null,
                    director == null ? null : director.getId(), null);
            lifecycle.settle(raised.getItems().get(1).getId(), ItemStatus.DONE, null,
                    director == null ? null : director.getId(), null);
            lifecycle.settle(raised.getItems().get(2).getId(), ItemStatus.BLOCKED,
                    "Waiting on the hardware supplier.", null, null);

            log.info("Seeded 2 checklist templates and 1 onboarding list for operator A");
        }));
    }

    private void addItem(ChecklistTemplate template, String title, ItemCategory category,
                         String ownerRole, int dueOffsetDays, boolean mandatory) {
        lifecycle.addTemplateItem(template.getId(), title, null, category, ownerRole,
                dueOffsetDays, mandatory, null);
    }
}
