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
class ProbationTest extends AbstractIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 1);
    private static final LocalDate PROBATION_END = LocalDate.of(2026, 6, 1);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private EmploymentContractService contracts;
    @Autowired
    private EmploymentContractRepository contractRepository;

    private UUID tenantA;
    private Employee manager;
    private int sequence;

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("P A", "p-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
        manager = employees.hire("EMP-300", "Sylvie", "Mbala", LocalDate.of(2019, 1, 7),
                null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private EmploymentContract onProbation() {
        return contract(PROBATION_END);
    }

    private EmploymentContract contract(LocalDate probationEnd) {
        Employee employee = employees.hire("EMP-4" + String.format("%02d", ++sequence),
                "Didier", "Lokwa", START, null, null);
        EmploymentContract contract = contracts.draft(employee.getId(), ContractType.PERMANENT,
                "Field Engineer", START, null, null, probationEnd, null);
        return contracts.activate(contract.getId(), null);
    }

    @Test
    @DisplayName("confirming records the outcome, the author and the moment")
    void confirms() {
        EmploymentContract contract = onProbation();

        EmploymentContract decided = contracts.decideProbation(contract.getId(),
                ProbationOutcome.CONFIRMED, "Settled in well", manager.getId(), null,
                UUID.randomUUID());

        assertThat(decided.getProbationOutcome()).isEqualTo(ProbationOutcome.CONFIRMED);
        assertThat(decided.getProbationDecidedAt()).isNotNull();
        assertThat(decided.getProbationDecidedBy().getId()).isEqualTo(manager.getId());
        assertThat(decided.getEmployee().getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    @DisplayName("a failed probation must say why")
    void failureNeedsReason() {
        EmploymentContract contract = onProbation();

        assertThatThrownBy(() -> contracts.decideProbation(contract.getId(),
                ProbationOutcome.FAILED, "  ", manager.getId(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a failed probation ends the employment in the same step")
    void failureTerminates() {
        EmploymentContract contract = onProbation();

        EmploymentContract decided = contracts.decideProbation(contract.getId(),
                ProbationOutcome.FAILED, "Repeated safety breaches", manager.getId(),
                PROBATION_END, null);

        assertThat(decided.getProbationOutcome()).isEqualTo(ProbationOutcome.FAILED);
        assertThat(decided.getEmployee().getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(decided.getEmployee().getTerminationDate()).isEqualTo(PROBATION_END);
        // The employee service closes the running contract as part of terminating.
        assertThat(decided.getStatus()).isEqualTo(ContractStatus.TERMINATED);
    }

    @Test
    @DisplayName("a failure with no last working day leaves on the probation end date")
    void failureDefaultsLeavingDate() {
        EmploymentContract contract = onProbation();

        EmploymentContract decided = contracts.decideProbation(contract.getId(),
                ProbationOutcome.FAILED, "Not meeting the standard", manager.getId(), null, null);

        assertThat(decided.getEmployee().getTerminationDate()).isEqualTo(PROBATION_END);
    }

    @Test
    @DisplayName("probation cannot be decided twice")
    void refusesSecondDecision() {
        EmploymentContract contract = onProbation();
        contracts.decideProbation(contract.getId(), ProbationOutcome.CONFIRMED, null,
                manager.getId(), null, null);

        assertThatThrownBy(() -> contracts.decideProbation(contract.getId(),
                ProbationOutcome.CONFIRMED, null, manager.getId(), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a contract with no probation period has no outcome to record")
    void refusesDecisionWithoutProbation() {
        EmploymentContract contract = contract(null);

        assertThatThrownBy(() -> contracts.decideProbation(contract.getId(),
                ProbationOutcome.CONFIRMED, null, manager.getId(), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("extending reopens the decision rather than settling it")
    void extensionReopensTheDecision() {
        EmploymentContract contract = onProbation();
        contracts.decideProbation(contract.getId(), ProbationOutcome.EXTENDED,
                "Needs another two months", manager.getId(), null, null);

        EmploymentContract extended = contracts.extendProbation(
                contract.getId(), PROBATION_END.plusMonths(2), null);

        assertThat(extended.getProbationEndDate()).isEqualTo(PROBATION_END.plusMonths(2));
        assertThat(extended.getProbationOutcome()).isNull();
        assertThat(extended.getProbationDecidedAt()).isNull();
    }

    @Test
    @DisplayName("an extension cannot shorten probation")
    void refusesShorteningExtension() {
        EmploymentContract contract = onProbation();

        assertThatThrownBy(() ->
                contracts.extendProbation(contract.getId(), PROBATION_END.minusDays(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the scan finds undecided probations and does not find decided ones")
    void scanFindsOnlyUndecided() {
        EmploymentContract undecided = onProbation();
        EmploymentContract settled = onProbation();
        contracts.decideProbation(settled.getId(), ProbationOutcome.CONFIRMED, null,
                manager.getId(), null, null);

        assertThat(contractRepository.findProbationEndingWithoutAlert(
                tenantA, PROBATION_END.plusDays(1)))
                .extracting(EmploymentContract::getId)
                .containsExactly(undecided.getId());
    }

    @Test
    @DisplayName("an alerted probation is not chased again")
    void scanDoesNotRepeat() {
        EmploymentContract contract = onProbation();
        contract.markProbationNotified();

        assertThat(contractRepository.findProbationEndingWithoutAlert(
                tenantA, PROBATION_END.plusDays(1)))
                .isEmpty();
    }
}
