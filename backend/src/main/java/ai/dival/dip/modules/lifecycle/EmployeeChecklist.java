package ai.dival.dip.modules.lifecycle;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One person's joining or leaving, as a list of work somebody owns.
 *
 * <p>The items are this employee's own copies. {@code templateName} records which list was used
 * and keeps saying so after the template is renamed or retired — a foreign key would let last
 * year's history be rewritten by this year's edit.
 */
@Entity
@Table(name = "employee_checklist")
public class EmployeeChecklist extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "checklist_type", nullable = false, length = 20)
    private ChecklistType checklistType;

    @Column(name = "template_name", nullable = false, length = 200)
    private String templateName;

    /** Hire date for onboarding, last working day for offboarding. Due dates hang off it. */
    @Column(name = "anchor_date", nullable = false)
    private LocalDate anchorDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChecklistStatus status = ChecklistStatus.IN_PROGRESS;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "checklist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ChecklistItem> items = new ArrayList<>();

    protected EmployeeChecklist() {
        // for JPA
    }

    public EmployeeChecklist(Employee employee, ChecklistType checklistType, String templateName,
                             LocalDate anchorDate) {
        this.employee = employee;
        this.checklistType = checklistType;
        this.templateName = templateName;
        this.anchorDate = anchorDate;
        this.status = ChecklistStatus.IN_PROGRESS;
    }

    /** Copies a template step into this list, resolving its due date against the anchor. */
    ChecklistItem copyFrom(ChecklistTemplateItem source, Employee assignee) {
        ChecklistItem item = new ChecklistItem(
                this,
                items.size() + 1,
                source.getTitle(),
                source.getInstructions(),
                source.getCategory(),
                assignee,
                anchorDate.plusDays(source.getDueOffsetDays()),
                source.isMandatory());
        items.add(item);
        return item;
    }

    /**
     * Closes the list.
     *
     * <p>Refuses while a mandatory item is outstanding. Being able to tick "offboarding complete"
     * over an unrevoked building pass is the failure this whole table exists to prevent.
     */
    public void complete() {
        if (status != ChecklistStatus.IN_PROGRESS) {
            throw new ConflictException("This checklist is already closed");
        }
        List<ChecklistItem> outstanding = outstandingMandatoryItems();
        if (!outstanding.isEmpty()) {
            throw new ConflictException(
                    "This checklist has " + outstanding.size()
                            + " required step(s) outstanding: " + outstanding.get(0).getTitle());
        }
        this.status = ChecklistStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Abandoned — a hire that fell through, a resignation withdrawn. */
    public void cancel() {
        if (status == ChecklistStatus.COMPLETED) {
            throw new ConflictException("A completed checklist cannot be cancelled");
        }
        this.status = ChecklistStatus.CANCELLED;
    }

    public List<ChecklistItem> outstandingMandatoryItems() {
        return items.stream()
                .filter(ChecklistItem::isMandatory)
                .filter(item -> !item.getStatus().isSettled())
                .toList();
    }

    public long settledCount() {
        return items.stream().filter(item -> item.getStatus().isSettled()).count();
    }

    public Employee getEmployee() {
        return employee;
    }

    public ChecklistType getChecklistType() {
        return checklistType;
    }

    public String getTemplateName() {
        return templateName;
    }

    public LocalDate getAnchorDate() {
        return anchorDate;
    }

    public ChecklistStatus getStatus() {
        return status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<ChecklistItem> getItems() {
        return List.copyOf(items);
    }
}
