package ai.dival.dip.modules.employees;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee records for the calling tenant.
 *
 * <p>Reading is open to authenticated members; a directory is of little use if only HR can see
 * it. Writing is restricted to HR, and personal details are returned only on the single-employee
 * view — a list endpoint that hands out everyone's date of birth and national identifier is a
 * breach waiting for a bug.
 */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private static final String HR_WRITE =
            "hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    private final EmployeeService employees;
    private final EmploymentContractService contracts;
    private final CurrentUserService currentUser;

    public EmployeeController(EmployeeService employees, EmploymentContractService contracts,
                              CurrentUserService currentUser) {
        this.employees = employees;
        this.contracts = contracts;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize(HR_WRITE)
    public List<EmployeeSummary> list() {
        return employees.list().stream().map(EmployeeSummary::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(HR_WRITE)
    public EmployeeDetail get(@PathVariable UUID id) {
        return EmployeeDetail.from(employees.get(id));
    }

    @GetMapping("/{id}/reports")
    @PreAuthorize(HR_WRITE)
    public List<EmployeeSummary> directReports(@PathVariable UUID id) {
        return employees.directReports(id).stream().map(EmployeeSummary::from).toList();
    }

    @GetMapping("/{id}/contracts")
    @PreAuthorize(HR_WRITE)
    public List<ContractResponse> contracts(@PathVariable UUID id) {
        return contracts.forEmployee(id).stream().map(ContractResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<EmployeeDetail> hire(@Valid @RequestBody HireRequest request) {
        Employee hired = employees.hire(
                request.employeeNumber(), request.firstName(), request.lastName(),
                request.hireDate(), request.orgUnitId(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EmployeeDetail.from(hired));
    }

    @PostMapping("/{id}/details")
    @PreAuthorize(HR_WRITE)
    public EmployeeDetail updateDetails(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateDetailsRequest request) {
        return EmployeeDetail.from(employees.updateDetails(
                id, request.firstName(), request.lastName(), request.preferredName(),
                request.dateOfBirth(), request.personalEmail(), request.phone(),
                request.nationalId(), actorId()));
    }

    @PostMapping("/{id}/org-unit")
    @PreAuthorize(HR_WRITE)
    public EmployeeDetail assign(@PathVariable UUID id, @RequestBody AssignRequest request) {
        return EmployeeDetail.from(employees.assignToOrgUnit(id, request.orgUnitId(), actorId()));
    }

    @GetMapping("/work-patterns")
    @PreAuthorize(HR_WRITE)
    public List<WorkPatternResponse> workPatterns() {
        return employees.listWorkPatterns().stream().map(WorkPatternResponse::from).toList();
    }

    @PostMapping("/work-patterns")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<WorkPatternResponse> createWorkPattern(
            @Valid @RequestBody WorkPatternRequest request) {
        WorkPattern created = employees.createWorkPattern(request.code(), request.name(),
                request.week(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkPatternResponse.from(created));
    }

    /** Null clears the pattern, putting somebody back on the default working week. */
    @PostMapping("/{id}/work-pattern")
    @PreAuthorize(HR_WRITE)
    public EmployeeDetail setWorkPattern(@PathVariable UUID id,
                                         @RequestBody WorkPatternAssignment request) {
        return EmployeeDetail.from(
                employees.setWorkPattern(id, request.workPatternId(), actorId()));
    }

    @PostMapping("/{id}/manager")
    @PreAuthorize(HR_WRITE)
    public EmployeeDetail setManager(@PathVariable UUID id, @RequestBody ManagerRequest request) {
        return EmployeeDetail.from(employees.setManager(id, request.managerId(), actorId()));
    }

    /**
     * Records how probation ended.
     *
     * <p>Against the contract rather than the employee, because probation belongs to the
     * engagement: somebody rehired later starts a fresh one.
     */
    @PostMapping("/contracts/{contractId}/probation")
    @PreAuthorize(HR_WRITE)
    public ContractResponse decideProbation(@PathVariable UUID contractId,
                                            @Valid @RequestBody ProbationRequest request) {
        return ContractResponse.from(contracts.decideProbation(
                contractId, request.outcome(), request.notes(), request.decidedByEmployeeId(),
                request.lastWorkingDay(), actorId()));
    }

    @PostMapping("/contracts/{contractId}/probation/extend")
    @PreAuthorize(HR_WRITE)
    public ContractResponse extendProbation(@PathVariable UUID contractId,
                                            @Valid @RequestBody ExtendProbationRequest request) {
        return ContractResponse.from(
                contracts.extendProbation(contractId, request.probationEndDate(), actorId()));
    }

    @PostMapping("/{id}/terminate")
    @PreAuthorize(HR_WRITE)
    public EmployeeDetail terminate(@PathVariable UUID id,
                                    @Valid @RequestBody TerminateRequest request) {
        return EmployeeDetail.from(
                employees.terminate(id, request.terminationDate(), actorId()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    public record HireRequest(
            @NotBlank String employeeNumber,
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotNull LocalDate hireDate,
            UUID orgUnitId) {
    }

    public record UpdateDetailsRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            String preferredName,
            LocalDate dateOfBirth,
            String personalEmail,
            String phone,
            String nationalId) {
    }

    public record AssignRequest(UUID orgUnitId) {
    }

    public record ManagerRequest(UUID managerId) {
    }

    /** @param week seven fractions, Monday first: 1 for a full day, 0.5 for a half, 0 for none */
    public record WorkPatternRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull BigDecimal[] week) {
    }

    public record WorkPatternAssignment(UUID workPatternId) {
    }

    public record TerminateRequest(@NotNull LocalDate terminationDate) {
    }

    /**
     * @param lastWorkingDay only read when the outcome is FAILED; defaults to the probation end
     *                       date, so a failed probation cannot leave somebody employed by accident
     */
    public record ProbationRequest(
            @NotNull ProbationOutcome outcome,
            String notes,
            UUID decidedByEmployeeId,
            LocalDate lastWorkingDay) {
    }

    public record ExtendProbationRequest(@NotNull LocalDate probationEndDate) {
    }

    /** Directory view. Deliberately carries no personal data beyond a name. */
    public record EmployeeSummary(
            UUID id,
            String employeeNumber,
            String displayName,
            EmployeeStatus status,
            UUID orgUnitId,
            String orgUnitName,
            UUID managerId) {

        static EmployeeSummary from(Employee employee) {
            return new EmployeeSummary(
                    employee.getId(),
                    employee.getEmployeeNumber(),
                    employee.displayName(),
                    employee.getStatus(),
                    employee.getOrgUnit() == null ? null : employee.getOrgUnit().getId(),
                    employee.getOrgUnit() == null ? null : employee.getOrgUnit().getName(),
                    employee.getManager() == null ? null : employee.getManager().getId());
        }
    }

    /** Full record, including personal data. Restricted to HR at the endpoint. */
    public record EmployeeDetail(
            UUID id,
            String employeeNumber,
            String firstName,
            String lastName,
            String preferredName,
            LocalDate dateOfBirth,
            String personalEmail,
            String phone,
            String nationalId,
            EmployeeStatus status,
            LocalDate hireDate,
            LocalDate terminationDate,
            UUID orgUnitId,
            UUID managerId,
            UUID workPatternId,
            String workPatternName,
            UUID userAccountId) {

        static EmployeeDetail from(Employee employee) {
            return new EmployeeDetail(
                    employee.getId(),
                    employee.getEmployeeNumber(),
                    employee.getFirstName(),
                    employee.getLastName(),
                    employee.getPreferredName(),
                    employee.getDateOfBirth(),
                    employee.getPersonalEmail(),
                    employee.getPhone(),
                    employee.getNationalId(),
                    employee.getStatus(),
                    employee.getHireDate(),
                    employee.getTerminationDate(),
                    employee.getOrgUnit() == null ? null : employee.getOrgUnit().getId(),
                    employee.getManager() == null ? null : employee.getManager().getId(),
                    employee.getWorkPattern() == null ? null : employee.getWorkPattern().getId(),
                    employee.getWorkPattern() == null ? null : employee.getWorkPattern().getName(),
                    employee.getUserAccountId());
        }
    }

    public record WorkPatternResponse(
            UUID id,
            String code,
            String name,
            BigDecimal monday,
            BigDecimal tuesday,
            BigDecimal wednesday,
            BigDecimal thursday,
            BigDecimal friday,
            BigDecimal saturday,
            BigDecimal sunday,
            BigDecimal weeklyDays,
            boolean active) {

        static WorkPatternResponse from(WorkPattern pattern) {
            return new WorkPatternResponse(
                    pattern.getId(),
                    pattern.getCode(),
                    pattern.getName(),
                    pattern.getMonday(),
                    pattern.getTuesday(),
                    pattern.getWednesday(),
                    pattern.getThursday(),
                    pattern.getFriday(),
                    pattern.getSaturday(),
                    pattern.getSunday(),
                    pattern.weeklyDays(),
                    pattern.isActive());
        }
    }

    public record ContractResponse(
            UUID id,
            UUID employeeId,
            ContractType contractType,
            String jobTitle,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate probationEndDate,
            ProbationOutcome probationOutcome,
            String probationNotes,
            ContractStatus status) {

        static ContractResponse from(EmploymentContract contract) {
            return new ContractResponse(
                    contract.getId(),
                    contract.getEmployee().getId(),
                    contract.getContractType(),
                    contract.getJobTitle(),
                    contract.getStartDate(),
                    contract.getEndDate(),
                    contract.getProbationEndDate(),
                    contract.getProbationOutcome(),
                    contract.getProbationNotes(),
                    contract.getStatus());
        }
    }
}
