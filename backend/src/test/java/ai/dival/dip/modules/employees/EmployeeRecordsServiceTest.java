package ai.dival.dip.modules.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.files.FileService;
import ai.dival.dip.modules.files.StoredFile;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class EmployeeRecordsServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private EmployeeRecordsService records;
    @Autowired
    private EmployeeDocumentRepository documentRepository;
    @Autowired
    private FileService files;

    private UUID tenantA;
    private UUID tenantB;
    private Employee employee;

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("R A", "r-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        tenantB = tenants.save(new Tenant("R B", "r-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
        employee = employees.hire("EMP-001", "Jean", "Kabila", LocalDate.of(2024, 1, 1), null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StoredFile uploadFile() {
        return files.upload("hello".getBytes(StandardCharsets.UTF_8),
                "permit.pdf", "application/pdf", "EMPLOYEE_DOCUMENT", null);
    }

    @Test
    @DisplayName("a dependent is recorded against the employee")
    void addsDependent() {
        EmployeeDependent child = records.addDependent(employee.getId(), "Grace Kabila",
                DependentRelationship.CHILD, LocalDate.of(2015, 6, 1), true, null);

        assertThat(child.getFullName()).isEqualTo("Grace Kabila");
        assertThat(child.isBeneficiary()).isTrue();
        assertThat(records.dependentsOf(employee.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a dependent cannot be born in the future")
    void refusesFutureDateOfBirth() {
        assertThatThrownBy(() -> records.addDependent(employee.getId(), "Future Child",
                DependentRelationship.CHILD, LocalDate.now().plusDays(1), false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an emergency contact must have a reachable number")
    void emergencyContactNeedsPhone() {
        assertThatThrownBy(() -> records.addEmergencyContact(employee.getId(), "Marie",
                "Sister", "  ", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("emergency contacts come back in call order")
    void emergencyContactsAreOrdered() {
        records.addEmergencyContact(employee.getId(), "Second Call", "Brother", "+243900000002", 2, null);
        records.addEmergencyContact(employee.getId(), "First Call", "Spouse", "+243900000001", 1, null);

        assertThat(records.emergencyContactsOf(employee.getId()))
                .extracting(EmergencyContact::getFullName)
                .containsExactly("First Call", "Second Call");
    }

    @Test
    @DisplayName("two contacts cannot share a priority")
    void refusesDuplicatePriority() {
        records.addEmergencyContact(employee.getId(), "First", "Spouse", "+243900000001", 1, null);

        assertThatThrownBy(() -> records.addEmergencyContact(
                employee.getId(), "Another", "Brother", "+243900000002", 1, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an uploaded file can be attached as a document")
    void attachesDocument() {
        StoredFile file = uploadFile();

        EmployeeDocument document = records.attachDocument(employee.getId(), file.getId(),
                DocumentType.WORK_PERMIT, "Work permit",
                LocalDate.of(2025, 1, 1), LocalDate.now().plusDays(20), null);

        assertThat(document.getStoredFileId()).isEqualTo(file.getId());
        assertThat(document.getDocumentType()).isEqualTo(DocumentType.WORK_PERMIT);
        assertThat(records.documentsOf(employee.getId())).hasSize(1);
    }

    @Test
    @DisplayName("the same file cannot be attached twice")
    void refusesDuplicateFile() {
        StoredFile file = uploadFile();
        records.attachDocument(employee.getId(), file.getId(), DocumentType.IDENTITY,
                "ID", null, null, null);

        assertThatThrownBy(() -> records.attachDocument(employee.getId(), file.getId(),
                DocumentType.IDENTITY, "ID again", null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a document cannot expire before it was issued")
    void refusesExpiryBeforeIssue() {
        StoredFile file = uploadFile();

        assertThatThrownBy(() -> records.attachDocument(employee.getId(), file.getId(),
                DocumentType.VISA, "Visa",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("expiring documents are found once, then not again")
    void documentExpiryScanAlertsOnce() {
        StoredFile file = uploadFile();
        EmployeeDocument document = records.attachDocument(employee.getId(), file.getId(),
                DocumentType.WORK_PERMIT, "Work permit", null, LocalDate.now().plusDays(10), null);
        documentRepository.flush();

        LocalDate cutoff = LocalDate.now().plusDays(30);
        assertThat(documentRepository.findExpiringWithoutAlert(tenantA, cutoff)).hasSize(1);

        document.markExpiryNotified();
        documentRepository.flush();
        assertThat(documentRepository.findExpiringWithoutAlert(tenantA, cutoff)).isEmpty();
    }

    @Test
    @DisplayName("renewing a document clears the warning so it can fire again")
    void renewalClearsAlert() {
        StoredFile file = uploadFile();
        EmployeeDocument document = records.attachDocument(employee.getId(), file.getId(),
                DocumentType.WORK_PERMIT, "Work permit", null, LocalDate.now().plusDays(10), null);
        document.markExpiryNotified();

        records.renewDocument(document.getId(), LocalDate.now().plusYears(2), null);

        assertThat(document.getExpiryNotifiedAt()).isNull();
        assertThat(document.getExpiresOn()).isEqualTo(LocalDate.now().plusYears(2));
    }

    @Test
    @DisplayName("records of another tenant's employee are unreachable")
    void recordsAreTenantScoped() {
        records.addDependent(employee.getId(), "Grace", DependentRelationship.CHILD, null, false, null);

        assertThatThrownBy(() ->
                TenantContext.runAs(tenantB, () -> records.dependentsOf(employee.getId())))
                .isInstanceOf(EmployeeService.EmployeeNotFoundException.class);
    }
}
