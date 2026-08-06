package ai.dival.dip.modules.employees;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.organizations.OrgUnitService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employment contracts and their lifecycle.
 *
 * <p>Contracts are created as drafts and activated deliberately. Preparing an offer is not the
 * same as the person being employed under it, and conflating the two makes headcount wrong for
 * as long as the paperwork takes.
 */
@Service
public class EmploymentContractService {

    private final EmploymentContractRepository contracts;
    private final EmployeeService employees;
    private final OrgUnitService orgUnits;
    private final AuditService audit;

    public EmploymentContractService(EmploymentContractRepository contracts,
                                     EmployeeService employees, OrgUnitService orgUnits,
                                     AuditService audit) {
        this.contracts = contracts;
        this.employees = employees;
        this.orgUnits = orgUnits;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<EmploymentContract> forEmployee(UUID employeeId) {
        return contracts.findByTenantIdAndEmployeeIdOrderByStartDateDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public EmploymentContract get(UUID id) {
        return contracts.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new ContractNotFoundException(id));
    }

    /** The contract someone is currently working under, if any. */
    @Transactional(readOnly = true)
    public java.util.Optional<EmploymentContract> current(UUID employeeId) {
        return contracts.findByTenantIdAndEmployeeIdAndStatus(
                TenantContext.require(), employeeId, ContractStatus.ACTIVE);
    }

    @Transactional
    public EmploymentContract draft(UUID employeeId, ContractType type, String jobTitle,
                                    LocalDate startDate, LocalDate endDate, UUID orgUnitId,
                                    LocalDate probationEndDate, UUID actorId) {
        Employee employee = employees.get(employeeId);

        if (jobTitle == null || jobTitle.isBlank()) {
            throw new IllegalArgumentException("A job title is required");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("A start date is required");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("A contract cannot end before it starts");
        }
        if (type.requiresEndDate() && endDate == null) {
            throw new IllegalArgumentException(
                    "A fixed-term contract must have an end date");
        }

        EmploymentContract contract =
                new EmploymentContract(employee, type, jobTitle, startDate, endDate);
        if (orgUnitId != null) {
            contract.setOrgUnit(orgUnits.get(orgUnitId));
        }
        if (probationEndDate != null) {
            if (probationEndDate.isBefore(startDate)) {
                throw new IllegalArgumentException("Probation cannot end before the contract starts");
            }
            contract.setProbationEndDate(probationEndDate);
        }

        EmploymentContract saved = contracts.save(contract);
        audit.recordSuccess("CONTRACT_DRAFTED", "EmploymentContract",
                saved.getId().toString(), actorId);
        return saved;
    }

    /**
     * Puts a contract in force.
     *
     * <p>Refuses if the employee already has one running. The database enforces this too, but a
     * clear error beats a constraint violation surfacing as a 500.
     */
    @Transactional
    public EmploymentContract activate(UUID id, UUID actorId) {
        UUID tenantId = TenantContext.require();
        EmploymentContract contract = get(id);

        if (contract.getStatus() == ContractStatus.ACTIVE) {
            return contract;
        }
        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new ConflictException("Only a draft contract can be activated");
        }

        contracts.findByTenantIdAndEmployeeIdAndStatus(
                        tenantId, contract.getEmployee().getId(), ContractStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This employee already has an active contract; end it first");
                });

        contract.activate();
        audit.recordSuccess("CONTRACT_ACTIVATED", "EmploymentContract", id.toString(), actorId);
        return contract;
    }

    /**
     * Records the probation decision.
     *
     * <p>A failed probation ends the employment in the same transaction. Recording the outcome
     * and leaving the person on the active headcount would produce two systems that disagree
     * about whether they still work here.
     *
     * @param decidedByEmployeeId who made the call, kept separate from whoever typed it in
     */
    @Transactional
    public EmploymentContract decideProbation(UUID id, ProbationOutcome outcome, String notes,
                                              UUID decidedByEmployeeId, LocalDate lastWorkingDay,
                                              UUID actorId) {
        EmploymentContract contract = get(id);
        Employee decidedBy =
                decidedByEmployeeId == null ? null : employees.get(decidedByEmployeeId);

        contract.decideProbation(outcome, notes, decidedBy);

        if (outcome == ProbationOutcome.FAILED) {
            LocalDate leavingOn =
                    lastWorkingDay == null ? contract.getProbationEndDate() : lastWorkingDay;
            employees.terminate(contract.getEmployee().getId(), leavingOn, actorId);
        }

        audit.recordSuccess("PROBATION_" + outcome, "EmploymentContract", id.toString(), actorId);
        return contract;
    }

    /** Gives probation more time, which reopens the decision rather than settling it. */
    @Transactional
    public EmploymentContract extendProbation(UUID id, LocalDate newProbationEnd, UUID actorId) {
        EmploymentContract contract = get(id);
        contract.extendProbation(newProbationEnd);
        audit.recordSuccess("PROBATION_PERIOD_EXTENDED", "EmploymentContract",
                id.toString(), actorId);
        return contract;
    }

    /** Closes a contract that reached its end date. */
    @Transactional
    public EmploymentContract end(UUID id, UUID actorId) {
        EmploymentContract contract = get(id);
        contract.end();
        audit.recordSuccess("CONTRACT_ENDED", "EmploymentContract", id.toString(), actorId);
        return contract;
    }

    /** Closes a contract early. Kept distinct from ending, because the reason matters. */
    @Transactional
    public EmploymentContract terminate(UUID id, UUID actorId) {
        EmploymentContract contract = get(id);
        contract.terminate();
        audit.recordSuccess("CONTRACT_TERMINATED", "EmploymentContract", id.toString(), actorId);
        return contract;
    }

    /** Pushes out the end date and clears the expiry alert so it can fire again later. */
    @Transactional
    public EmploymentContract extend(UUID id, LocalDate newEndDate, UUID actorId) {
        EmploymentContract contract = get(id);
        if (!contract.getStatus().isCurrent()) {
            throw new ConflictException("Only an active contract can be extended");
        }
        contract.extendTo(newEndDate);
        audit.recordSuccess("CONTRACT_EXTENDED", "EmploymentContract", id.toString(), actorId);
        return contract;
    }

    public static class ContractNotFoundException extends ResourceNotFoundException {
        public ContractNotFoundException(UUID id) {
            super("Employment contract not found: " + id);
        }
    }
}
