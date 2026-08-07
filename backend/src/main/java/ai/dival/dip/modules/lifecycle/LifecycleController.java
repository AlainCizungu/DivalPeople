package ai.dival.dip.modules.lifecycle;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Onboarding and offboarding.
 *
 * <p>Reading an open checklist is left to any authenticated member, because the people who own
 * the steps are rarely in HR — a manager who cannot see the list they are on will not work
 * through it. Raising and closing lists, and editing templates, stay with HR.
 */
@RestController
@RequestMapping("/api/v1/lifecycle")
public class LifecycleController {

    private static final String HR_WRITE =
            "hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    private final LifecycleService lifecycle;
    private final CurrentUserService currentUser;

    public LifecycleController(LifecycleService lifecycle, CurrentUserService currentUser) {
        this.lifecycle = lifecycle;
        this.currentUser = currentUser;
    }

    // --- templates ---------------------------------------------------------

    @GetMapping("/templates")
    @PreAuthorize(HR_WRITE)
    public List<TemplateResponse> listTemplates() {
        return lifecycle.listTemplates().stream().map(TemplateResponse::from).toList();
    }

    @GetMapping("/templates/{id}")
    @PreAuthorize(HR_WRITE)
    public TemplateResponse template(@PathVariable UUID id) {
        return TemplateResponse.from(lifecycle.template(id));
    }

    @PostMapping("/templates")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<TemplateResponse> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request) {
        ChecklistTemplate created = lifecycle.createTemplate(
                request.code(), request.name(), request.checklistType(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TemplateResponse.from(created));
    }

    @PostMapping("/templates/{id}/items")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<TemplateResponse> addTemplateItem(
            @PathVariable UUID id, @Valid @RequestBody AddTemplateItemRequest request) {
        lifecycle.addTemplateItem(id, request.title(), request.instructions(),
                request.category(), request.ownerRole(), request.dueOffsetDays(),
                request.mandatory(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TemplateResponse.from(lifecycle.template(id)));
    }

    @PostMapping("/templates/{id}/retire")
    @PreAuthorize(HR_WRITE)
    public TemplateResponse retireTemplate(@PathVariable UUID id) {
        return TemplateResponse.from(lifecycle.retireTemplate(id, actorId()));
    }

    // --- checklists --------------------------------------------------------

    @GetMapping("/checklists")
    @PreAuthorize(HR_WRITE)
    public List<ChecklistSummary> open() {
        return lifecycle.openChecklists().stream().map(ChecklistSummary::from).toList();
    }

    @GetMapping("/checklists/{id}")
    @PreAuthorize(HR_WRITE)
    public ChecklistDetail checklist(@PathVariable UUID id) {
        return ChecklistDetail.from(lifecycle.checklist(id));
    }

    @GetMapping("/employees/{employeeId}/checklists")
    @PreAuthorize(HR_WRITE)
    public List<ChecklistSummary> forEmployee(@PathVariable UUID employeeId) {
        return lifecycle.checklistsFor(employeeId).stream().map(ChecklistSummary::from).toList();
    }

    @PostMapping("/checklists")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<ChecklistDetail> raise(@Valid @RequestBody RaiseRequest request) {
        EmployeeChecklist raised = lifecycle.raise(
                request.employeeId(), request.templateId(), request.anchorDate(),
                request.defaultAssigneeId(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ChecklistDetail.from(raised));
    }

    @PostMapping("/checklists/{id}/complete")
    @PreAuthorize(HR_WRITE)
    public ChecklistDetail complete(@PathVariable UUID id) {
        return ChecklistDetail.from(lifecycle.completeChecklist(id, actorId()));
    }

    @PostMapping("/checklists/{id}/cancel")
    @PreAuthorize(HR_WRITE)
    public ChecklistDetail cancel(@PathVariable UUID id) {
        return ChecklistDetail.from(lifecycle.cancelChecklist(id, actorId()));
    }

    // --- items -------------------------------------------------------------

    /** Open to any member: the person who owns a step is the one who should tick it off. */
    @PostMapping("/items/{id}/status")
    @PreAuthorize(HR_WRITE)
    public ItemResponse settle(@PathVariable UUID id, @Valid @RequestBody SettleRequest request) {
        return ItemResponse.from(lifecycle.settle(id, request.status(), request.notes(),
                request.completedByEmployeeId(), actorId()));
    }

    @PostMapping("/items/{id}/assignee")
    @PreAuthorize(HR_WRITE)
    public ItemResponse assign(@PathVariable UUID id, @RequestBody AssignRequest request) {
        return ItemResponse.from(lifecycle.assign(id, request.assigneeEmployeeId(), actorId()));
    }

    @PostMapping("/items/{id}/due-date")
    @PreAuthorize(HR_WRITE)
    public ItemResponse reschedule(@PathVariable UUID id,
                                   @Valid @RequestBody RescheduleRequest request) {
        return ItemResponse.from(lifecycle.reschedule(id, request.dueOn(), actorId()));
    }

    @PostMapping("/items/{id}/reopen")
    @PreAuthorize(HR_WRITE)
    public ItemResponse reopen(@PathVariable UUID id) {
        return ItemResponse.from(lifecycle.reopen(id, actorId()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    // --- requests ----------------------------------------------------------

    public record CreateTemplateRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull ChecklistType checklistType) {
    }

    public record AddTemplateItemRequest(
            @NotBlank String title,
            String instructions,
            @NotNull ItemCategory category,
            String ownerRole,
            int dueOffsetDays,
            boolean mandatory) {
    }

    public record RaiseRequest(
            @NotNull UUID employeeId,
            @NotNull UUID templateId,
            @NotNull LocalDate anchorDate,
            UUID defaultAssigneeId) {
    }

    public record SettleRequest(
            @NotNull ItemStatus status,
            String notes,
            UUID completedByEmployeeId) {
    }

    public record AssignRequest(UUID assigneeEmployeeId) {
    }

    public record RescheduleRequest(@NotNull LocalDate dueOn) {
    }

    // --- responses ---------------------------------------------------------

    public record TemplateResponse(
            UUID id,
            String code,
            String name,
            ChecklistType checklistType,
            boolean active,
            List<TemplateItemResponse> items) {

        static TemplateResponse from(ChecklistTemplate template) {
            return new TemplateResponse(
                    template.getId(),
                    template.getCode(),
                    template.getName(),
                    template.getChecklistType(),
                    template.isActive(),
                    template.getItems().stream().map(TemplateItemResponse::from).toList());
        }
    }

    public record TemplateItemResponse(
            UUID id,
            int sortOrder,
            String title,
            String instructions,
            ItemCategory category,
            String ownerRole,
            int dueOffsetDays,
            boolean mandatory) {

        static TemplateItemResponse from(ChecklistTemplateItem item) {
            return new TemplateItemResponse(
                    item.getId(),
                    item.getSortOrder(),
                    item.getTitle(),
                    item.getInstructions(),
                    item.getCategory(),
                    item.getOwnerRole(),
                    item.getDueOffsetDays(),
                    item.isMandatory());
        }
    }

    /** Enough to list checklists without loading every step of every one. */
    public record ChecklistSummary(
            UUID id,
            UUID employeeId,
            String employeeName,
            ChecklistType checklistType,
            String templateName,
            LocalDate anchorDate,
            ChecklistStatus status,
            long settledCount,
            int itemCount,
            int outstandingMandatory) {

        static ChecklistSummary from(EmployeeChecklist checklist) {
            return new ChecklistSummary(
                    checklist.getId(),
                    checklist.getEmployee().getId(),
                    checklist.getEmployee().displayName(),
                    checklist.getChecklistType(),
                    checklist.getTemplateName(),
                    checklist.getAnchorDate(),
                    checklist.getStatus(),
                    checklist.settledCount(),
                    checklist.getItems().size(),
                    checklist.outstandingMandatoryItems().size());
        }
    }

    public record ChecklistDetail(
            UUID id,
            UUID employeeId,
            String employeeName,
            ChecklistType checklistType,
            String templateName,
            LocalDate anchorDate,
            ChecklistStatus status,
            Instant completedAt,
            List<ItemResponse> items) {

        static ChecklistDetail from(EmployeeChecklist checklist) {
            return new ChecklistDetail(
                    checklist.getId(),
                    checklist.getEmployee().getId(),
                    checklist.getEmployee().displayName(),
                    checklist.getChecklistType(),
                    checklist.getTemplateName(),
                    checklist.getAnchorDate(),
                    checklist.getStatus(),
                    checklist.getCompletedAt(),
                    checklist.getItems().stream().map(ItemResponse::from).toList());
        }
    }

    public record ItemResponse(
            UUID id,
            UUID checklistId,
            int sortOrder,
            String title,
            String instructions,
            ItemCategory category,
            UUID assigneeId,
            String assigneeName,
            LocalDate dueOn,
            boolean mandatory,
            ItemStatus status,
            Instant completedAt,
            String notes) {

        static ItemResponse from(ChecklistItem item) {
            return new ItemResponse(
                    item.getId(),
                    item.getChecklist().getId(),
                    item.getSortOrder(),
                    item.getTitle(),
                    item.getInstructions(),
                    item.getCategory(),
                    item.getAssignee() == null ? null : item.getAssignee().getId(),
                    item.getAssignee() == null ? null : item.getAssignee().displayName(),
                    item.getDueOn(),
                    item.isMandatory(),
                    item.getStatus(),
                    item.getCompletedAt(),
                    item.getNotes());
        }
    }
}
