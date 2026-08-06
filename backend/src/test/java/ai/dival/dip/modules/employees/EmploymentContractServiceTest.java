package ai.dival.dip.modules.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
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
class EmploymentContractServiceTest extends AbstractIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private EmploymentContractService contracts;
    @Autowired
    private EmploymentContractRepository contractRepository;

    private Employee employee;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("C A", "c-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);
        employee = employees.hire("EMP-001", "Jean", "Kabila", LocalDate.of(2025, 1, 1), null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private EmploymentContract draftFixedTerm() {
        return contracts.draft(employee.getId(), ContractType.FIXED_TERM, "Engineer",
                START, END, null, null, null);
    }

    @Test
    @DisplayName("a contract starts as a draft, not in force")
    void startsAsDraft() {
        assertThat(draftFixedTerm().getStatus()).isEqualTo(ContractStatus.DRAFT);
    }

    @Test
    @DisplayName("a fixed-term contract must carry an end date")
    void fixedTermNeedsEndDate() {
        assertThatThrownBy(() -> contracts.draft(employee.getId(), ContractType.FIXED_TERM,
                "Engineer", START, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // A permanent one legitimately has none.
        assertThat(contracts.draft(employee.getId(), ContractType.PERMANENT, "Engineer",
                START, null, null, null, null).getEndDate()).isNull();
    }

    @Test
    @DisplayName("a contract cannot end before it starts")
    void refusesEndBeforeStart() {
        assertThatThrownBy(() -> contracts.draft(employee.getId(), ContractType.FIXED_TERM,
                "Engineer", END, START, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("activating puts the contract in force")
    void activates() {
        EmploymentContract contract = draftFixedTerm();

        contracts.activate(contract.getId(), null);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(contracts.current(employee.getId())).isPresent();
    }

    @Test
    @DisplayName("only one contract can be active at a time")
    void refusesSecondActiveContract() {
        contracts.activate(draftFixedTerm().getId(), null);
        contractRepository.flush();

        EmploymentContract second = draftFixedTerm();

        assertThatThrownBy(() -> contracts.activate(second.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("only a draft can be activated")
    void refusesActivatingEndedContract() {
        EmploymentContract contract = draftFixedTerm();
        contracts.activate(contract.getId(), null);
        contracts.end(contract.getId(), null);

        assertThatThrownBy(() -> contracts.activate(contract.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("extending pushes the end date out and clears the expiry alert")
    void extends_() {
        EmploymentContract contract = draftFixedTerm();
        contracts.activate(contract.getId(), null);
        contract.markExpiryNotified();
        assertThat(contract.getExpiryNotifiedAt()).isNotNull();

        contracts.extend(contract.getId(), END.plusYears(1), null);

        assertThat(contract.getEndDate()).isEqualTo(END.plusYears(1));
        // The alert described the old date, so it has to be allowed to fire again.
        assertThat(contract.getExpiryNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("an extension cannot shorten a contract")
    void refusesShorteningExtension() {
        EmploymentContract contract = draftFixedTerm();
        contracts.activate(contract.getId(), null);

        assertThatThrownBy(() -> contracts.extend(contract.getId(), START.plusDays(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("terminating the employee also closes their running contract")
    void terminationClosesContract() {
        EmploymentContract contract = draftFixedTerm();
        contracts.activate(contract.getId(), null);
        contractRepository.flush();

        employees.terminate(employee.getId(), LocalDate.of(2026, 6, 30), null);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.TERMINATED);
        assertThat(contracts.current(employee.getId())).isEmpty();
    }

    @Test
    @DisplayName("the expiry scan finds running dated contracts before their end")
    void findsExpiringContracts() {
        EmploymentContract contract = draftFixedTerm();
        contracts.activate(contract.getId(), null);
        contractRepository.flush();

        UUID tenantId = TenantContext.require();
        assertThat(contractRepository.findExpiringWithoutAlert(tenantId, END)).hasSize(1);

        // Once alerted, it drops out — a daily scan must not repeat itself.
        contract.markExpiryNotified();
        contractRepository.flush();
        assertThat(contractRepository.findExpiringWithoutAlert(tenantId, END)).isEmpty();
    }

    @Test
    @DisplayName("drafts and permanent contracts never appear in the expiry scan")
    void expiryScanIgnoresDraftsAndPermanent() {
        draftFixedTerm(); // left as a draft
        EmploymentContract permanent = contracts.draft(employee.getId(), ContractType.PERMANENT,
                "Engineer", START, null, null, null, null);
        contracts.activate(permanent.getId(), null);
        contractRepository.flush();

        assertThat(contractRepository.findExpiringWithoutAlert(
                TenantContext.require(), END.plusYears(10))).isEmpty();
    }
}
