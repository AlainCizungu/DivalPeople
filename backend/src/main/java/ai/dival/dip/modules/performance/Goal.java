package ai.dival.dip.modules.performance;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Something somebody is trying to achieve.
 *
 * <p>The cycle is optional. A goal can outlive the cycle it was written in, and plenty are set
 * outside any cycle at all — requiring one would push people into inventing cycles to hold goals.
 */
@Entity
@Table(name = "goal")
public class Goal extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id")
    private ReviewCycle cycle;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    /** How success will be recognised. Free text: a measure that fits a dropdown measures little. */
    @Column(name = "measure", length = 1000)
    private String measure;

    @Column(name = "weight", nullable = false, precision = 5, scale = 2)
    private BigDecimal weight = BigDecimal.ONE;

    /** Cascading goals: this one contributes to that one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supports_goal_id")
    private Goal supports;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GoalStatus status = GoalStatus.DRAFT;

    @Column(name = "outcome_notes", length = 2000)
    private String outcomeNotes;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Goal() {
        // for JPA
    }

    public Goal(Employee employee, String title, String measure, LocalDate targetDate) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A goal needs a title");
        }
        this.employee = employee;
        this.title = title.trim();
        this.measure = measure;
        this.targetDate = targetDate;
        this.status = GoalStatus.DRAFT;
    }

    public void describe(String description, BigDecimal weight) {
        if (weight != null && weight.signum() <= 0) {
            throw new IllegalArgumentException("A weight must be positive");
        }
        this.description = description;
        if (weight != null) {
            this.weight = weight;
        }
    }

    public void placeInCycle(ReviewCycle cycle) {
        this.cycle = cycle;
    }

    /**
     * Links this goal to the one it contributes to.
     *
     * <p>Refuses a goal supporting itself. Deeper loops are the service's problem, since only it
     * can walk the chain.
     */
    void supportGoal(Goal parent) {
        if (parent != null && parent.getId() != null && parent.getId().equals(getId())) {
            throw new IllegalArgumentException("A goal cannot support itself");
        }
        this.supports = parent;
    }

    public void activate() {
        if (status != GoalStatus.DRAFT) {
            throw new ConflictException("Only a draft goal can be activated");
        }
        this.status = GoalStatus.ACTIVE;
    }

    public void recordProgress(int percent) {
        if (status.isClosed()) {
            throw new ConflictException("This goal is closed");
        }
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Progress is a percentage between 0 and 100");
        }
        this.progressPercent = percent;
    }

    /**
     * Closes the goal with an outcome.
     *
     * <p>Anything short of achievement needs an explanation. "Missed", standing alone in somebody's
     * record a year later, can only be read uncharitably.
     */
    public void close(GoalStatus outcome, String notes) {
        if (status.isClosed()) {
            throw new ConflictException("This goal is already closed");
        }
        if (outcome == null || !outcome.isClosed()) {
            throw new IllegalArgumentException("Closing a goal needs an outcome");
        }
        if (outcome.needsExplanation() && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException(
                    "Anything other than achievement needs an explanation");
        }

        this.status = outcome;
        this.outcomeNotes = notes;
        this.closedAt = Instant.now();
        if (outcome == GoalStatus.ACHIEVED) {
            this.progressPercent = 100;
        }
    }

    public Employee getEmployee() {
        return employee;
    }

    public ReviewCycle getCycle() {
        return cycle;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getMeasure() {
        return measure;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public Goal getSupports() {
        return supports;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public String getOutcomeNotes() {
        return outcomeNotes;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
