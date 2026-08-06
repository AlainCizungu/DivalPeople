package ai.dival.dip.modules.employees;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The records that hang off an employee: dependents, emergency contacts and documents.
 *
 * <p>Grouped in one service because they share a single rule — they exist only in relation to an
 * employee, and every operation must confirm that employee belongs to the calling tenant before
 * touching anything. Splitting them into three services would triple that check and its chances
 * of being forgotten.
 */
@Service
public class EmployeeRecordsService {

    private final EmployeeService employees;
    private final EmployeeDependentRepository dependents;
    private final EmergencyContactRepository emergencyContacts;
    private final EmployeeDocumentRepository documents;
    private final AuditService audit;

    public EmployeeRecordsService(EmployeeService employees,
                                  EmployeeDependentRepository dependents,
                                  EmergencyContactRepository emergencyContacts,
                                  EmployeeDocumentRepository documents,
                                  AuditService audit) {
        this.employees = employees;
        this.dependents = dependents;
        this.emergencyContacts = emergencyContacts;
        this.documents = documents;
        this.audit = audit;
    }

    // --- dependents --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EmployeeDependent> dependentsOf(UUID employeeId) {
        employees.get(employeeId); // confirms the employee is ours before returning anything
        return dependents.findByTenantIdAndEmployeeIdOrderByFullNameAsc(
                TenantContext.require(), employeeId);
    }

    @Transactional
    public EmployeeDependent addDependent(UUID employeeId, String fullName,
                                          DependentRelationship relationship,
                                          LocalDate dateOfBirth, boolean beneficiary,
                                          UUID actorId) {
        Employee employee = employees.get(employeeId);
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("A dependent needs a name");
        }
        if (relationship == null) {
            throw new IllegalArgumentException("A dependent needs a relationship");
        }
        if (dateOfBirth != null && dateOfBirth.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("A date of birth cannot be in the future");
        }

        EmployeeDependent dependent = dependents.save(new EmployeeDependent(
                employee, fullName, relationship, dateOfBirth, beneficiary));
        audit.recordSuccess("DEPENDENT_ADDED", "EmployeeDependent",
                dependent.getId().toString(), actorId);
        return dependent;
    }

    @Transactional
    public EmployeeDependent updateDependent(UUID id, String fullName,
                                             DependentRelationship relationship,
                                             LocalDate dateOfBirth, boolean beneficiary,
                                             UUID actorId) {
        EmployeeDependent dependent = dependents.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new DependentNotFoundException(id));
        dependent.update(fullName, relationship, dateOfBirth, beneficiary);
        audit.recordSuccess("DEPENDENT_UPDATED", "EmployeeDependent", id.toString(), actorId);
        return dependent;
    }

    // --- emergency contacts ------------------------------------------------

    @Transactional(readOnly = true)
    public List<EmergencyContact> emergencyContactsOf(UUID employeeId) {
        employees.get(employeeId);
        return emergencyContacts.findByTenantIdAndEmployeeIdOrderByPriorityAsc(
                TenantContext.require(), employeeId);
    }

    /**
     * Adds a contact at a given priority.
     *
     * <p>Priorities are unique per employee, so "who do we call first" is never ambiguous — which
     * matters because this list is read under pressure by someone unfamiliar with it.
     */
    @Transactional
    public EmergencyContact addEmergencyContact(UUID employeeId, String fullName,
                                                String relationship, String phone,
                                                int priority, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);

        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("An emergency contact needs a name");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("An emergency contact needs a phone number");
        }
        if (relationship == null || relationship.isBlank()) {
            throw new IllegalArgumentException("An emergency contact needs a relationship");
        }
        if (priority < 1) {
            throw new IllegalArgumentException("Priority starts at 1");
        }
        if (emergencyContacts.existsByTenantIdAndEmployeeIdAndPriority(
                tenantId, employeeId, priority)) {
            throw new ConflictException(
                    "This employee already has an emergency contact at priority " + priority);
        }

        EmergencyContact contact = emergencyContacts.save(
                new EmergencyContact(employee, fullName, relationship, phone, priority));
        audit.recordSuccess("EMERGENCY_CONTACT_ADDED", "EmergencyContact",
                contact.getId().toString(), actorId);
        return contact;
    }

    @Transactional
    public EmergencyContact updateEmergencyContact(UUID id, String fullName, String relationship,
                                                   String phone, String alternatePhone,
                                                   String email, UUID actorId) {
        EmergencyContact contact = emergencyContacts
                .findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new EmergencyContactNotFoundException(id));
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("An emergency contact needs a phone number");
        }
        contact.update(fullName, relationship, phone, alternatePhone, email);
        audit.recordSuccess("EMERGENCY_CONTACT_UPDATED", "EmergencyContact",
                id.toString(), actorId);
        return contact;
    }

    // --- documents ---------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EmployeeDocument> documentsOf(UUID employeeId) {
        employees.get(employeeId);
        return documents.findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
                TenantContext.require(), employeeId);
    }

    /**
     * Attaches an already-uploaded file to an employee.
     *
     * <p>Upload happens first, through the files module, so this never handles bytes. That keeps
     * the size, type and checksum rules in exactly one place.
     */
    @Transactional
    public EmployeeDocument attachDocument(UUID employeeId, UUID storedFileId,
                                           DocumentType documentType, String title,
                                           LocalDate issuedOn, LocalDate expiresOn,
                                           UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);

        if (storedFileId == null) {
            throw new IllegalArgumentException("A document needs an uploaded file");
        }
        if (documentType == null) {
            throw new IllegalArgumentException("A document needs a type");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A document needs a title");
        }
        if (expiresOn != null && issuedOn != null && expiresOn.isBefore(issuedOn)) {
            throw new IllegalArgumentException("A document cannot expire before it was issued");
        }
        documents.findByTenantIdAndStoredFileId(tenantId, storedFileId).ifPresent(existing -> {
            throw new ConflictException("That file is already attached as a document");
        });

        EmployeeDocument document = documents.save(new EmployeeDocument(
                employee, storedFileId, documentType, title, issuedOn, expiresOn));
        audit.recordSuccess("DOCUMENT_ATTACHED", "EmployeeDocument",
                document.getId().toString(), actorId);
        return document;
    }

    /** Records a renewal, which also clears the previous expiry warning. */
    @Transactional
    public EmployeeDocument renewDocument(UUID id, LocalDate newExpiry, UUID actorId) {
        EmployeeDocument document = documents.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new DocumentNotFoundException(id));
        document.renewUntil(newExpiry);
        audit.recordSuccess("DOCUMENT_RENEWED", "EmployeeDocument", id.toString(), actorId);
        return document;
    }

    public static class DependentNotFoundException extends ResourceNotFoundException {
        public DependentNotFoundException(UUID id) {
            super("Dependent not found: " + id);
        }
    }

    public static class EmergencyContactNotFoundException extends ResourceNotFoundException {
        public EmergencyContactNotFoundException(UUID id) {
            super("Emergency contact not found: " + id);
        }
    }

    public static class DocumentNotFoundException extends ResourceNotFoundException {
        public DocumentNotFoundException(UUID id) {
            super("Employee document not found: " + id);
        }
    }
}
