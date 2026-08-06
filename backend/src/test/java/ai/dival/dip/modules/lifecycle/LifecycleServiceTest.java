package ai.dival.dip.modules.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class LifecycleServiceTest extends AbstractIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private LifecycleService lifecycle;
    @Autowired
    private EmployeeService employees;

    private UUID tenantA;
    private Employee joiner;
    private Employee owner;

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("L A", "l-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
        joiner = employees.hire("EMP-200", "Didier", "Lokwa", START, null, null);
        owner = employees.hire("EMP-201", "Sylvie", "Mbala", LocalDate.of(2020, 1, 6),
                null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ChecklistTemplate onboardingTemplate(String code) {
        ChecklistTemplate template = lifecycle.createTemplate(
                code, "Standard onboarding", ChecklistType.ONBOARDING, null);
        lifecycle.addTemplateItem(template.getId(), "Send the signed contract", null,
                ItemCategory.PAPERWORK, "HR_ADMIN", -10, true, null);
        lifecycle.addTemplateItem(template.getId(), "Prepare laptop", null,
                ItemCategory.EQUIPMENT, null, -2, false, null);
        lifecycle.addTemplateItem(template.getId(), "Create accounts", null,
                ItemCategory.ACCESS, null, -1, true, null);
        return template;
    }

    private EmployeeChecklist raise(String code) {
        return lifecycle.raise(joiner.getId(), onboardingTemplate(code).getId(), START,
                owner.getId(), null);
    }

    // --- templates ---------------------------------------------------------

    @Test
    @DisplayName("template codes are normalised and unique within a tenant")
    void normalisesTemplateCode() {
        ChecklistTemplate template = lifecycle.createTemplate(
                "  onb std ", "Standard onboarding", ChecklistType.ONBOARDING, null);

        assertThat(template.getCode()).isEqualTo("ONB-STD");
        assertThatThrownBy(() -> lifecycle.createTemplate("onb-std", "Another",
                ChecklistType.ONBOARDING, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a retired template cannot raise new lists")
    void refusesRetiredTemplate() {
        ChecklistTemplate template = onboardingTemplate("ONB-1");
        lifecycle.retireTemplate(template.getId(), null);

        assertThatThrownBy(() ->
                lifecycle.raise(joiner.getId(), template.getId(), START, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an empty template raises nothing")
    void refusesEmptyTemplate() {
        ChecklistTemplate empty = lifecycle.createTemplate(
                "ONB-EMPTY", "Nothing to do", ChecklistType.ONBOARDING, null);

        assertThatThrownBy(() ->
                lifecycle.raise(joiner.getId(), empty.getId(), START, null, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- raising -----------------------------------------------------------

    @Test
    @DisplayName("raising copies the template's steps and resolves their dates")
    void copiesTemplateItems() {
        EmployeeChecklist checklist = raise("ONB-2");

        assertThat(checklist.getItems()).hasSize(3);
        assertThat(checklist.getItems().get(0).getTitle()).isEqualTo("Send the signed contract");
        // -10 days from the anchor: the work that makes a first day go well happens before it.
        assertThat(checklist.getItems().get(0).getDueOn()).isEqualTo(START.minusDays(10));
        assertThat(checklist.getItems().get(2).getDueOn()).isEqualTo(START.minusDays(1));
        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("the copy survives the template being renamed")
    void copyIsIndependentOfTemplate() {
        ChecklistTemplate template = onboardingTemplate("ONB-3");
        EmployeeChecklist checklist =
                lifecycle.raise(joiner.getId(), template.getId(), START, null, null);

        template.rename("Completely different list");
        template.addItem("A step invented later", null, ItemCategory.OTHER, null, 0, true);

        assertThat(checklist.getTemplateName()).isEqualTo("Standard onboarding");
        assertThat(checklist.getItems()).hasSize(3);
    }

    @Test
    @DisplayName("every step gets an owner when one is given")
    void assignsDefaultOwner() {
        EmployeeChecklist checklist = raise("ONB-4");

        assertThat(checklist.getItems())
                .allSatisfy(item -> assertThat(item.getAssignee().getId())
                        .isEqualTo(owner.getId()));
    }

    @Test
    @DisplayName("one open list of each kind per person")
    void refusesSecondOpenChecklist() {
        raise("ONB-5");

        assertThatThrownBy(() -> raise("ONB-6")).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a cancelled list leaves room for a new one")
    void allowsNewChecklistAfterCancel() {
        EmployeeChecklist first = raise("ONB-7");
        lifecycle.cancelChecklist(first.getId(), null);

        EmployeeChecklist second = raise("ONB-8");

        assertThat(second.getStatus()).isEqualTo(ChecklistStatus.IN_PROGRESS);
        assertThat(first.getStatus()).isEqualTo(ChecklistStatus.CANCELLED);
    }

    // --- items -------------------------------------------------------------

    @Test
    @DisplayName("blocking a step needs an explanation")
    void requiresNotesToBlock() {
        ChecklistItem item = raise("ONB-9").getItems().get(1);

        assertThatThrownBy(() -> lifecycle.settle(item.getId(), ItemStatus.BLOCKED, "  ",
                null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("skipping a step needs an explanation")
    void requiresNotesToSkip() {
        ChecklistItem item = raise("ONB-10").getItems().get(1);

        assertThatThrownBy(() -> lifecycle.settle(item.getId(), ItemStatus.NOT_APPLICABLE, null,
                null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("completing a step records who did it, not only who typed it")
    void recordsWhoCompleted() {
        ChecklistItem item = raise("ONB-11").getItems().get(1);

        ChecklistItem done = lifecycle.settle(item.getId(), ItemStatus.DONE, null,
                owner.getId(), UUID.randomUUID());

        assertThat(done.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(done.getCompletedBy().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("a moved deadline lets the overdue alert fire again")
    void reschedulingClearsAlert() {
        ChecklistItem item = raise("ONB-12").getItems().get(0);
        item.markOverdueNotified();

        lifecycle.reschedule(item.getId(), START.plusDays(5), null);

        assertThat(item.getDueOn()).isEqualTo(START.plusDays(5));
        assertThat(item.getOverdueNotifiedAt()).isNull();
    }

    // --- closing -----------------------------------------------------------

    @Test
    @DisplayName("a list cannot be closed while a required step is outstanding")
    void refusesCompletionWithOutstandingMandatoryItem() {
        EmployeeChecklist checklist = raise("ONB-13");
        // Only the optional one is done.
        lifecycle.settle(checklist.getItems().get(1).getId(), ItemStatus.DONE, null, null, null);

        assertThatThrownBy(() -> lifecycle.completeChecklist(checklist.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a required step marked not applicable counts as settled")
    void skippedMandatoryItemUnblocksCompletion() {
        EmployeeChecklist checklist = raise("ONB-14");
        List<ChecklistItem> items = checklist.getItems();

        lifecycle.settle(items.get(0).getId(), ItemStatus.DONE, null, null, null);
        lifecycle.settle(items.get(2).getId(), ItemStatus.NOT_APPLICABLE,
                "Contractor, no accounts needed.", null, null);

        EmployeeChecklist completed = lifecycle.completeChecklist(checklist.getId(), null);

        assertThat(completed.getStatus()).isEqualTo(ChecklistStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        // The optional step was never touched, and that is allowed.
        assertThat(items.get(1).getStatus()).isEqualTo(ItemStatus.PENDING);
    }

    @Test
    @DisplayName("a closed list cannot be edited")
    void refusesEditsOnClosedChecklist() {
        EmployeeChecklist checklist = raise("ONB-15");
        ChecklistItem item = checklist.getItems().get(1);
        lifecycle.cancelChecklist(checklist.getId(), null);

        assertThatThrownBy(() -> lifecycle.settle(item.getId(), ItemStatus.DONE, null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a completed list cannot be cancelled afterwards")
    void refusesCancelAfterCompletion() {
        EmployeeChecklist checklist = raise("ONB-16");
        checklist.getItems().stream()
                .filter(ChecklistItem::isMandatory)
                .forEach(item -> lifecycle.settle(item.getId(), ItemStatus.DONE, null, null, null));
        lifecycle.completeChecklist(checklist.getId(), null);

        assertThatThrownBy(() -> lifecycle.cancelChecklist(checklist.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    // --- isolation ---------------------------------------------------------

    @Test
    @DisplayName("one tenant's checklists are invisible to another")
    void checklistsDoNotCrossTenants() {
        EmployeeChecklist checklist = raise("ONB-17");

        UUID tenantB = tenants.save(new Tenant("L B", "l-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantB);

        assertThatThrownBy(() -> lifecycle.checklist(checklist.getId()))
                .isInstanceOf(LifecycleService.ChecklistNotFoundException.class);

        TenantContext.set(tenantA);
    }
}
