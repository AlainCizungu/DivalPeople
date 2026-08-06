package ai.dival.dip.modules.learning;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.files.StoredFile;
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

/**
 * One person's attempt at one course.
 *
 * <p>An attempt is never deleted. A failure stays on the record, and a fresh attempt is a new row,
 * because a history that cannot distinguish "passed first time" from "passed on the fourth
 * attempt" cannot answer the question an investigation asks after an incident.
 */
@Entity
@Table(name = "course_enrolment")
public class CourseEnrolment extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EnrolmentStatus status = EnrolmentStatus.ENROLLED;

    @Column(name = "enrolled_on", nullable = false)
    private LocalDate enrolledOn = LocalDate.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_on")
    private LocalDate completedOn;

    @Column(name = "score")
    private Integer score;

    /**
     * When this qualification lapses.
     *
     * <p>Computed from the course's validity at completion and then left alone. Shortening the
     * validity period next year must not retrospectively invalidate a certificate somebody
     * already holds and has been working under.
     */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certificate_file_id")
    private StoredFile certificate;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    protected CourseEnrolment() {
        // for JPA
    }

    public CourseEnrolment(Employee employee, Course course, LocalDate enrolledOn) {
        this.employee = employee;
        this.course = course;
        this.enrolledOn = enrolledOn == null ? LocalDate.now() : enrolledOn;
        this.status = EnrolmentStatus.ENROLLED;
    }

    public void start() {
        if (status != EnrolmentStatus.ENROLLED) {
            throw new ConflictException("Only an enrolment not yet started can be started");
        }
        this.status = EnrolmentStatus.IN_PROGRESS;
        this.startedAt = Instant.now();
    }

    /**
     * Records the outcome of an attempt.
     *
     * <p>Whether it is a pass is the course's decision, not the caller's — otherwise the same
     * score means different things depending on who typed it in.
     */
    public void complete(LocalDate completedOn, Integer score, String notes) {
        if (status.isFinished()) {
            throw new ConflictException("This attempt is already finished");
        }
        LocalDate day = completedOn == null ? LocalDate.now() : completedOn;
        if (day.isBefore(enrolledOn)) {
            throw new IllegalArgumentException("A course cannot be completed before it was begun");
        }
        if (score != null && (score < 0 || score > 100)) {
            throw new IllegalArgumentException("A score is a percentage");
        }

        this.completedOn = day;
        this.score = score;
        this.notes = notes;

        if (course.isPass(score)) {
            this.status = EnrolmentStatus.COMPLETED;
            this.expiresOn = course.expiryFor(day);
        } else {
            this.status = EnrolmentStatus.FAILED;
        }
    }

    public void withdraw(String reason) {
        if (status.isFinished()) {
            throw new ConflictException("This attempt is already finished");
        }
        this.status = EnrolmentStatus.WITHDRAWN;
        this.notes = reason;
    }

    public void attachCertificate(StoredFile certificate) {
        if (status != EnrolmentStatus.COMPLETED) {
            throw new ConflictException("Only a completed course has a certificate");
        }
        this.certificate = certificate;
    }

    /**
     * Marks a lapsed qualification.
     *
     * <p>Swept rather than derived on read, so a lapsed certificate reads the same on every
     * screen instead of depending on which code path asked — and so the transition itself has a
     * moment, which a compliance report needs.
     */
    public void expire() {
        if (status != EnrolmentStatus.COMPLETED) {
            throw new ConflictException("Only a completed course can expire");
        }
        this.status = EnrolmentStatus.EXPIRED;
    }

    /** Puts an expiry back where a certificate was renewed by the awarding body. */
    public void renewUntil(LocalDate newExpiry) {
        if (status != EnrolmentStatus.COMPLETED && status != EnrolmentStatus.EXPIRED) {
            throw new ConflictException("Only a completed or lapsed qualification can be renewed");
        }
        if (newExpiry == null || (completedOn != null && !newExpiry.isAfter(completedOn))) {
            throw new IllegalArgumentException("A renewal must extend beyond the completion date");
        }
        this.expiresOn = newExpiry;
        this.status = EnrolmentStatus.COMPLETED;
        // The alert was about the previous date, so it must be allowed to fire again.
        this.expiryNotifiedAt = null;
    }

    public boolean hasLapsedBy(LocalDate day) {
        return status == EnrolmentStatus.COMPLETED
                && expiresOn != null
                && expiresOn.isBefore(day);
    }

    /** Whether this counts as currently holding the qualification on a given day. */
    public boolean isValidOn(LocalDate day) {
        return status.isValidQualification() && (expiresOn == null || !expiresOn.isBefore(day));
    }

    public void markExpiryNotified() {
        this.expiryNotifiedAt = Instant.now();
    }

    public Employee getEmployee() {
        return employee;
    }

    public Course getCourse() {
        return course;
    }

    public EnrolmentStatus getStatus() {
        return status;
    }

    public LocalDate getEnrolledOn() {
        return enrolledOn;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public LocalDate getCompletedOn() {
        return completedOn;
    }

    public Integer getScore() {
        return score;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public StoredFile getCertificate() {
        return certificate;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getExpiryNotifiedAt() {
        return expiryNotifiedAt;
    }
}
