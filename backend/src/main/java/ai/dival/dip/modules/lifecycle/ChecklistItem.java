package ai.dival.dip.modules.lifecycle;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** One step somebody has to do, on one person's list. */
@Entity
@Table(name = "checklist_item")
public class ChecklistItem extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checklist_id", nullable = false)
    private EmployeeChecklist checklist;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "instructions", length = 2000)
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ItemCategory category = ItemCategory.OTHER;

    /** A person, not a role: "who is doing this" should have an answer. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private Employee assignee;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ItemStatus status = ItemStatus.PENDING;

    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by")
    private Employee completedBy;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

    protected ChecklistItem() {
        // for JPA
    }

    ChecklistItem(EmployeeChecklist checklist, int sortOrder, String title, String instructions,
                  ItemCategory category, Employee assignee, LocalDate dueOn, boolean mandatory) {
        this.checklist = checklist;
        this.sortOrder = sortOrder;
        this.title = title;
        this.instructions = instructions;
        this.category = category == null ? ItemCategory.OTHER : category;
        this.assignee = assignee;
        this.dueOn = dueOn;
        this.mandatory = mandatory;
        this.status = ItemStatus.PENDING;
    }

    public void assignTo(Employee assignee) {
        requireOpen();
        this.assignee = assignee;
    }

    public void reschedule(LocalDate dueOn) {
        requireOpen();
        this.dueOn = dueOn;
        // A moved deadline is a new deadline, so the alert is allowed to fire again.
        this.overdueNotifiedAt = null;
    }

    /**
     * Records the outcome of a step.
     *
     * <p>Blocking or skipping requires notes. A blocked item with no explanation is a task nobody
     * can pick up, and a mandatory step marked "not applicable" in silence is indistinguishable
     * from one quietly dropped.
     *
     * @param completedBy who did it; null when the actor is not an employee record
     */
    public void settle(ItemStatus next, String notes, Employee completedBy) {
        requireOpen();
        if (next == null || next == ItemStatus.PENDING) {
            throw new IllegalArgumentException("An item can only be moved to a settled state");
        }
        if (next.needsExplanation() && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException(
                    "Blocking or skipping a step needs an explanation");
        }

        this.status = next;
        this.notes = notes;
        if (next == ItemStatus.DONE || next == ItemStatus.NOT_APPLICABLE) {
            this.completedAt = Instant.now();
            this.completedBy = completedBy;
        }
    }

    /** Puts a blocked item back in play once whatever held it up is cleared. */
    public void reopen() {
        if (status == ItemStatus.PENDING) {
            return;
        }
        if (!checklist.getStatus().isOpen()) {
            throw new ConflictException("This checklist is closed");
        }
        this.status = ItemStatus.PENDING;
        this.completedAt = null;
        this.completedBy = null;
        this.overdueNotifiedAt = null;
    }

    public boolean isOverdueOn(LocalDate day) {
        return status == ItemStatus.PENDING && dueOn != null && dueOn.isBefore(day);
    }

    public void markOverdueNotified() {
        this.overdueNotifiedAt = Instant.now();
    }

    private void requireOpen() {
        if (!checklist.getStatus().isOpen()) {
            throw new ConflictException("This checklist is closed");
        }
    }

    public EmployeeChecklist getChecklist() {
        return checklist;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getTitle() {
        return title;
    }

    public String getInstructions() {
        return instructions;
    }

    public ItemCategory getCategory() {
        return category;
    }

    public Employee getAssignee() {
        return assignee;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public ItemStatus getStatus() {
        return status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Employee getCompletedBy() {
        return completedBy;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getOverdueNotifiedAt() {
        return overdueNotifiedAt;
    }
}
