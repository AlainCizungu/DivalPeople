package ai.dival.dip.modules.employees;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Who to call if something happens to an employee.
 *
 * <p>Read in the worst moment of someone's working life, usually by a person who has never opened
 * this screen before. A phone number is mandatory — a contact nobody can reach is not a contact —
 * and priority 1 is unambiguous about who to try first.
 */
@Entity
@Table(name = "employee_emergency_contact")
public class EmergencyContact extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "full_name", nullable = false, length = 300)
    private String fullName;

    /** Free text rather than an enum: "my neighbour Chantal" is a legitimate answer. */
    @Column(name = "relationship", nullable = false, length = 100)
    private String relationship;

    @Column(name = "phone", nullable = false, length = 40)
    private String phone;

    @Column(name = "alternate_phone", length = 40)
    private String alternatePhone;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "priority", nullable = false)
    private int priority = 1;

    protected EmergencyContact() {
        // for JPA
    }

    public EmergencyContact(Employee employee, String fullName, String relationship,
                            String phone, int priority) {
        this.employee = employee;
        this.fullName = trim(fullName);
        this.relationship = trim(relationship);
        this.phone = trim(phone);
        this.priority = priority;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public void update(String fullName, String relationship, String phone,
                       String alternatePhone, String email) {
        this.fullName = trim(fullName);
        this.relationship = trim(relationship);
        this.phone = trim(phone);
        this.alternatePhone = trim(alternatePhone);
        this.email = trim(email);
    }

    public void reprioritise(int priority) {
        this.priority = priority;
    }

    public Employee getEmployee() {
        return employee;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRelationship() {
        return relationship;
    }

    public String getPhone() {
        return phone;
    }

    public String getAlternatePhone() {
        return alternatePhone;
    }

    public String getEmail() {
        return email;
    }

    public int getPriority() {
        return priority;
    }
}
