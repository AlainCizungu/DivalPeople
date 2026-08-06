package ai.dival.dip.modules.employees;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.organizations.OrgUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * A person employed by a tenant.
 *
 * <p>Reporting lines are the {@code manager} reference rather than a separate structure: who
 * someone reports to is a fact about them, and keeping it here means it cannot disagree with an
 * org chart stored elsewhere.
 *
 * <p>Distinct from a user account. Most employees never sign in — field staff, factory workers —
 * so the link to a login is optional and must stay that way.
 */
@Entity
@Table(name = "employee")
public class Employee extends TenantOwnedEntity {

    @Column(name = "employee_number", nullable = false, length = 50)
    private String employeeNumber;

    @Column(name = "first_name", nullable = false, length = 150)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 150)
    private String lastName;

    @Column(name = "preferred_name", length = 150)
    private String preferredName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "national_id", length = 100)
    private String nationalId;

    @Column(name = "personal_email", length = 320)
    private String personalEmail;

    @Column(name = "phone", length = 40)
    private String phone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_unit_id")
    private OrgUnit orgUnit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Employee manager;

    /** The employee's login, when they have one. Stored as an id to avoid coupling the modules. */
    @Column(name = "user_account_id")
    private UUID userAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    protected Employee() {
        // for JPA
    }

    public Employee(String employeeNumber, String firstName, String lastName, LocalDate hireDate) {
        this.employeeNumber = normalizeNumber(employeeNumber);
        this.firstName = trim(firstName);
        this.lastName = trim(lastName);
        this.hireDate = hireDate;
        this.status = EmployeeStatus.ACTIVE;
    }

    /** Employee numbers are matched by payroll imports, so case and spacing must not vary. */
    public static String normalizeNumber(String number) {
        if (number == null) {
            return "";
        }
        return number.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** What to call this person: their preferred name if they gave one, otherwise their first. */
    public String displayName() {
        String given = preferredName != null && !preferredName.isBlank() ? preferredName : firstName;
        return (given + " " + lastName).trim();
    }

    public void updatePersonalDetails(String firstName, String lastName, String preferredName,
                                      LocalDate dateOfBirth, String personalEmail, String phone,
                                      String nationalId) {
        this.firstName = trim(firstName);
        this.lastName = trim(lastName);
        this.preferredName = trim(preferredName);
        this.dateOfBirth = dateOfBirth;
        this.personalEmail = trim(personalEmail);
        this.phone = trim(phone);
        this.nationalId = trim(nationalId);
    }

    public void assignTo(OrgUnit orgUnit) {
        this.orgUnit = orgUnit;
    }

    /** Cycle detection lives in the service, which can walk the whole chain. */
    void reportTo(Employee manager) {
        this.manager = manager;
    }

    public void linkUserAccount(UUID userAccountId) {
        this.userAccountId = userAccountId;
    }

    public void changeStatus(EmployeeStatus status) {
        if (status == EmployeeStatus.TERMINATED) {
            throw new IllegalArgumentException("Use terminate() so a leaving date is recorded");
        }
        this.status = status;
    }

    /**
     * Ends the employment. The row is kept — payroll history, audit entries and any declared
     * records still point at it.
     */
    public void terminate(LocalDate terminationDate) {
        if (terminationDate == null) {
            throw new IllegalArgumentException("A termination date is required");
        }
        if (terminationDate.isBefore(hireDate)) {
            throw new IllegalArgumentException("Termination cannot precede the hire date");
        }
        this.status = EmployeeStatus.TERMINATED;
        this.terminationDate = terminationDate;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPreferredName() {
        return preferredName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getNationalId() {
        return nationalId;
    }

    public String getPersonalEmail() {
        return personalEmail;
    }

    public String getPhone() {
        return phone;
    }

    public OrgUnit getOrgUnit() {
        return orgUnit;
    }

    public Employee getManager() {
        return manager;
    }

    public UUID getUserAccountId() {
        return userAccountId;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public LocalDate getTerminationDate() {
        return terminationDate;
    }
}
