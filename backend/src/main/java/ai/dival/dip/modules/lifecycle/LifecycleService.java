package ai.dival.dip.modules.lifecycle;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Joining and leaving: checklist templates, and the lists raised from them.
 *
 * <p>Raising a list copies the template's steps rather than pointing at them. A template is how a
 * list began; the copy is what somebody was actually asked to do, and that has to stay true after
 * the template is edited.
 */
@Service
public class LifecycleService {

    private final ChecklistTemplateRepository templates;
    private final EmployeeChecklistRepository checklists;
    private final ChecklistItemRepository items;
    private final EmployeeService employees;
    private final AuditService audit;

    public LifecycleService(ChecklistTemplateRepository templates,
                            EmployeeChecklistRepository checklists,
                            ChecklistItemRepository items,
                            EmployeeService employees,
                            AuditService audit) {
        this.templates = templates;
        this.checklists = checklists;
        this.items = items;
        this.employees = employees;
        this.audit = audit;
    }

    // --- templates ---------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChecklistTemplate> listTemplates() {
        return templates.findByTenantIdOrderByNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public ChecklistTemplate template(UUID id) {
        return templates.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new TemplateNotFoundException(id));
    }

    @Transactional
    public ChecklistTemplate createTemplate(String code, String name, ChecklistType type,
                                            UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A template needs a name");
        }
        String normalized = ChecklistTemplate.normalizeCode(code);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A template code is required");
        }
        if (templates.findByTenantIdAndCode(tenantId, normalized).isPresent()) {
            throw new ConflictException("Template code already in use: " + normalized);
        }

        ChecklistTemplate saved = templates.save(new ChecklistTemplate(normalized, name, type));
        audit.recordSuccess("CHECKLIST_TEMPLATE_CREATED", "ChecklistTemplate",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public ChecklistTemplateItem addTemplateItem(UUID templateId, String title,
                                                 String instructions, ItemCategory category,
                                                 String ownerRole, int dueOffsetDays,
                                                 boolean mandatory, UUID actorId) {
        ChecklistTemplate template = template(templateId);
        ChecklistTemplateItem item = template.addItem(
                title, instructions, category, ownerRole, dueOffsetDays, mandatory);
        audit.recordSuccess("CHECKLIST_TEMPLATE_ITEM_ADDED", "ChecklistTemplate",
                templateId.toString(), actorId);
        return item;
    }

    @Transactional
    public ChecklistTemplate retireTemplate(UUID id, UUID actorId) {
        ChecklistTemplate template = template(id);
        template.retire();
        audit.recordSuccess("CHECKLIST_TEMPLATE_RETIRED", "ChecklistTemplate",
                id.toString(), actorId);
        return template;
    }

    // --- checklists --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EmployeeChecklist> checklistsFor(UUID employeeId) {
        employees.get(employeeId);
        return checklists.findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public List<EmployeeChecklist> openChecklists() {
        return checklists.findByTenantIdAndStatusOrderByAnchorDateAsc(
                TenantContext.require(), ChecklistStatus.IN_PROGRESS);
    }

    @Transactional(readOnly = true)
    public EmployeeChecklist checklist(UUID id) {
        return checklists.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new ChecklistNotFoundException(id));
    }

    /**
     * Raises a list for somebody, copying the template's steps into their own.
     *
     * <p>Every step is assigned to a person up front. A role is what a template can say; a name is
     * what makes somebody feel asked, and an unassigned checklist is a list everybody assumes
     * somebody else is working through.
     *
     * @param anchorDate hire date for onboarding, last working day for offboarding; every due
     *                   date is derived from it
     * @param defaultAssigneeId who owns the steps whose role resolves to nobody
     */
    @Transactional
    public EmployeeChecklist raise(UUID employeeId, UUID templateId, LocalDate anchorDate,
                                   UUID defaultAssigneeId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);
        ChecklistTemplate template = template(templateId);

        if (!template.isActive()) {
            throw new ConflictException("This template has been retired");
        }
        if (anchorDate == null) {
            throw new IllegalArgumentException(
                    "A checklist needs an anchor date to hang its deadlines on");
        }
        if (template.getItems().isEmpty()) {
            throw new ConflictException("This template has no steps");
        }
        checklists.findByTenantIdAndEmployeeIdAndChecklistTypeAndStatus(
                tenantId, employeeId, template.getChecklistType(), ChecklistStatus.IN_PROGRESS)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This employee already has an open "
                                    + template.getChecklistType().name().toLowerCase()
                                    + " checklist");
                });

        Employee assignee = defaultAssigneeId == null ? null : employees.get(defaultAssigneeId);

        EmployeeChecklist checklist = new EmployeeChecklist(
                employee, template.getChecklistType(), template.getName(), anchorDate);
        template.getItems().forEach(source -> checklist.copyFrom(source, assignee));

        EmployeeChecklist saved = checklists.save(checklist);
        audit.recordSuccess("CHECKLIST_RAISED", "EmployeeChecklist",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public EmployeeChecklist completeChecklist(UUID id, UUID actorId) {
        EmployeeChecklist checklist = checklist(id);
        checklist.complete();
        audit.recordSuccess("CHECKLIST_COMPLETED", "EmployeeChecklist", id.toString(), actorId);
        return checklist;
    }

    @Transactional
    public EmployeeChecklist cancelChecklist(UUID id, UUID actorId) {
        EmployeeChecklist checklist = checklist(id);
        checklist.cancel();
        audit.recordSuccess("CHECKLIST_CANCELLED", "EmployeeChecklist", id.toString(), actorId);
        return checklist;
    }

    // --- items -------------------------------------------------------------

    @Transactional(readOnly = true)
    public ChecklistItem item(UUID id) {
        return items.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new ChecklistItemNotFoundException(id));
    }

    @Transactional
    public ChecklistItem assign(UUID itemId, UUID assigneeEmployeeId, UUID actorId) {
        ChecklistItem item = item(itemId);
        item.assignTo(assigneeEmployeeId == null ? null : employees.get(assigneeEmployeeId));
        audit.recordSuccess("CHECKLIST_ITEM_ASSIGNED", "ChecklistItem",
                itemId.toString(), actorId);
        return item;
    }

    @Transactional
    public ChecklistItem reschedule(UUID itemId, LocalDate dueOn, UUID actorId) {
        ChecklistItem item = item(itemId);
        item.reschedule(dueOn);
        audit.recordSuccess("CHECKLIST_ITEM_RESCHEDULED", "ChecklistItem",
                itemId.toString(), actorId);
        return item;
    }

    /**
     * Records what happened to a step.
     *
     * @param completedByEmployeeId the person who did it, which is not always the actor — HR often
     *                              records that IT revoked an account
     */
    @Transactional
    public ChecklistItem settle(UUID itemId, ItemStatus status, String notes,
                                UUID completedByEmployeeId, UUID actorId) {
        ChecklistItem item = item(itemId);
        Employee completedBy =
                completedByEmployeeId == null ? null : employees.get(completedByEmployeeId);
        item.settle(status, notes, completedBy);
        audit.recordSuccess("CHECKLIST_ITEM_" + status, "ChecklistItem",
                itemId.toString(), actorId);
        return item;
    }

    @Transactional
    public ChecklistItem reopen(UUID itemId, UUID actorId) {
        ChecklistItem item = item(itemId);
        item.reopen();
        audit.recordSuccess("CHECKLIST_ITEM_REOPENED", "ChecklistItem", itemId.toString(), actorId);
        return item;
    }

    public static class TemplateNotFoundException extends ResourceNotFoundException {
        public TemplateNotFoundException(UUID id) {
            super("Checklist template not found: " + id);
        }
    }

    public static class ChecklistNotFoundException extends ResourceNotFoundException {
        public ChecklistNotFoundException(UUID id) {
            super("Checklist not found: " + id);
        }
    }

    public static class ChecklistItemNotFoundException extends ResourceNotFoundException {
        public ChecklistItemNotFoundException(UUID id) {
            super("Checklist item not found: " + id);
        }
    }
}
