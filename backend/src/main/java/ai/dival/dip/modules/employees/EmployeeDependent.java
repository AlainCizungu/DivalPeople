package ai.dival.dip.modules.employees;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Someone an employee supports — a partner, a child, a parent.
 *
 * <p>Personal data about a person who never agreed to anything with the employer, and often a
 * minor. Retention follows the employee: when their record goes, this goes with it.
 */
@Entity
@Table(name = "employee_dependent")
public class EmployeeDependent extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "full_name", nullable = false, length = 300)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, length = 30)
    private DependentRelationship relationship;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Named on insurance or pension. Drives benefit enrolment. */
    @Column(name = "beneficiary", nullable = false)
    private boolean beneficiary;

    protected EmployeeDependent() {
        // for JPA
    }

    public EmployeeDependent(Employee employee, String fullName,
                             DependentRelationship relationship, LocalDate dateOfBirth,
                             boolean beneficiary) {
        this.employee = employee;
        this.fullName = fullName == null ? null : fullName.trim();
        this.relationship = relationship;
        this.dateOfBirth = dateOfBirth;
        this.beneficiary = beneficiary;
    }

    public void update(String fullName, DependentRelationship relationship,
                       LocalDate dateOfBirth, boolean beneficiary) {
        this.fullName = fullName == null ? this.fullName : fullName.trim();
        this.relationship = relationship;
        this.dateOfBirth = dateOfBirth;
        this.beneficiary = beneficiary;
    }

    public Employee getEmployee() {
        return employee;
    }

    public String getFullName() {
        return fullName;
    }

    public DependentRelationship getRelationship() {
        return relationship;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public boolean isBeneficiary() {
        return beneficiary;
    }
}
