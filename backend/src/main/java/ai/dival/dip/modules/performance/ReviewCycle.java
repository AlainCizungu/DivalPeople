package ai.dival.dip.modules.performance;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A period everybody is reviewed against, and the window in which that happens. */
@Entity
@Table(name = "review_cycle")
public class ReviewCycle extends TenantOwnedEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** When reviews are due, which is usually after the period it covers has ended. */
    @Column(name = "due_on")
    private LocalDate dueOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CycleStatus status = CycleStatus.DRAFT;

    protected ReviewCycle() {
        // for JPA
    }

    public ReviewCycle(String name, LocalDate periodStart, LocalDate periodEnd, LocalDate dueOn) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A cycle needs a name");
        }
        if (periodStart == null || periodEnd == null) {
            throw new IllegalArgumentException("A cycle needs a period");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("A period cannot end before it starts");
        }
        this.name = name.trim();
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.dueOn = dueOn;
        this.status = CycleStatus.DRAFT;
    }

    public void open() {
        if (status != CycleStatus.DRAFT) {
            throw new ConflictException("Only a draft cycle can be opened");
        }
        this.status = CycleStatus.OPEN;
    }

    /**
     * Closes the cycle.
     *
     * <p>Reviews already written stay readable. Closing stops new ones being submitted, it does
     * not hide what people were told about their year.
     */
    public void close() {
        if (status != CycleStatus.OPEN) {
            throw new ConflictException("Only an open cycle can be closed");
        }
        this.status = CycleStatus.CLOSED;
    }

    public void cancel() {
        if (status == CycleStatus.CLOSED) {
            throw new ConflictException("A closed cycle cannot be cancelled");
        }
        this.status = CycleStatus.CANCELLED;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(periodStart) && !day.isAfter(periodEnd);
    }

    public String getName() {
        return name;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public CycleStatus getStatus() {
        return status;
    }
}
