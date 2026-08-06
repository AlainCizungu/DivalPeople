package ai.dival.dip.modules.recruitment;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;

/**
 * Someone who has applied, or might.
 *
 * <p>A person rather than an application: three applications from the same address are one
 * candidate, which is what makes "have we spoken to them before" answerable.
 *
 * <p>Candidates are the only people in the platform with no relationship to the employer at all.
 * Most will be rejected, and their data should not linger indefinitely — retention rules per
 * country are required before production.
 */
@Entity
@Table(name = "candidate")
public class Candidate extends TenantOwnedEntity {

    @Column(name = "first_name", nullable = false, length = 150)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 150)
    private String lastName;

    /** Identifies the person, which is why it is unique per tenant. */
    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private CandidateSource source = CandidateSource.DIRECT;

    @Column(name = "notes", length = 4000)
    private String notes;

    /** Set when they are hired, linking the candidate to the person they became. */
    @Column(name = "employee_id")
    private UUID employeeId;

    protected Candidate() {
        // for JPA
    }

    public Candidate(String firstName, String lastName, String email, CandidateSource source) {
        this.firstName = trim(firstName);
        this.lastName = trim(lastName);
        this.email = normalizeEmail(email);
        this.source = source == null ? CandidateSource.DIRECT : source;
    }

    /** Addresses are matched, so case and stray spacing must not create a second person. */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public String displayName() {
        return (firstName + " " + lastName).trim();
    }

    public void update(String firstName, String lastName, String phone, String notes) {
        this.firstName = trim(firstName);
        this.lastName = trim(lastName);
        this.phone = trim(phone);
        this.notes = notes;
    }

    public void linkEmployee(UUID employeeId) {
        this.employeeId = employeeId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public CandidateSource getSource() {
        return source;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }
}
