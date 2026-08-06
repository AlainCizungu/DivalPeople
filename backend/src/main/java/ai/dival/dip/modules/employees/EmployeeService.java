package ai.dival.dip.modules.employees;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.organizations.OrgUnit;
import ai.dival.dip.modules.organizations.OrgUnitService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People employed by the calling tenant.
 *
 * <p>Everything is scoped to {@link TenantContext}. Structural rules that need a view beyond one
 * row — a reporting line that would close a loop, a termination that must also close a contract —
 * are enforced here rather than in the entity.
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final EmploymentContractRepository contracts;
    private final OrgUnitService orgUnits;
    private final WorkPatternRepository patterns;
    private final AuditService audit;

    public EmployeeService(EmployeeRepository employees, EmploymentContractRepository contracts,
                           OrgUnitService orgUnits, WorkPatternRepository patterns,
                           AuditService audit) {
        this.employees = employees;
        this.contracts = contracts;
        this.orgUnits = orgUnits;
        this.patterns = patterns;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Employee> list() {
        return employees.findByTenantIdOrderByLastNameAscFirstNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public Employee get(UUID id) {
        return employees.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new EmployeeNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Employee> directReports(UUID managerId) {
        return employees.findByTenantIdAndManagerId(TenantContext.require(), managerId);
    }

    @Transactional(readOnly = true)
    public long headcount() {
        return employees.countByTenantIdAndStatus(TenantContext.require(), EmployeeStatus.ACTIVE);
    }

    @Transactional
    public Employee hire(String employeeNumber, String firstName, String lastName,
                         LocalDate hireDate, UUID orgUnitId, UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            throw new IllegalArgumentException("An employee needs a first and last name");
        }
        if (hireDate == null) {
            throw new IllegalArgumentException("A hire date is required");
        }

        String number = Employee.normalizeNumber(employeeNumber);
        if (number.isBlank()) {
            throw new IllegalArgumentException("An employee number is required");
        }
        if (employees.findByTenantIdAndEmployeeNumber(tenantId, number).isPresent()) {
            throw new EmployeeNumberAlreadyUsedException(number);
        }

        Employee employee = new Employee(number, firstName, lastName, hireDate);
        if (orgUnitId != null) {
            employee.assignTo(orgUnits.get(orgUnitId));
        }

        Employee saved = employees.save(employee);
        audit.recordSuccess("EMPLOYEE_HIRED", "Employee", saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public Employee updateDetails(UUID id, String firstName, String lastName, String preferredName,
                                  LocalDate dateOfBirth, String personalEmail, String phone,
                                  String nationalId, UUID actorId) {
        Employee employee = get(id);
        employee.updatePersonalDetails(
                firstName, lastName, preferredName, dateOfBirth, personalEmail, phone, nationalId);
        audit.recordSuccess("EMPLOYEE_UPDATED", "Employee", id.toString(), actorId);
        return employee;
    }

    @Transactional
    public Employee assignToOrgUnit(UUID id, UUID orgUnitId, UUID actorId) {
        Employee employee = get(id);
        OrgUnit unit = orgUnitId == null ? null : orgUnits.get(orgUnitId);
        employee.assignTo(unit);
        audit.recordSuccess("EMPLOYEE_ASSIGNED", "Employee", id.toString(), actorId);
        return employee;
    }

    /**
     * Sets who someone reports to.
     *
     * <p>Refuses a loop. A manager chain that closes on itself makes every question that walks it
     * — approvals, escalation, org charts — run forever, while each individual row still looks
     * perfectly valid.
     */
    @Transactional
    public Employee setManager(UUID id, UUID managerId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = get(id);

        if (managerId == null) {
            employee.reportTo(null);
        } else {
            if (managerId.equals(id)) {
                throw new IllegalArgumentException("An employee cannot report to themselves");
            }
            Set<UUID> reports = Set.copyOf(employees.findAllReportIds(tenantId, id));
            if (reports.contains(managerId)) {
                throw new IllegalArgumentException(
                        "An employee cannot report to someone who reports to them");
            }
            employee.reportTo(get(managerId));
        }

        audit.recordSuccess("EMPLOYEE_MANAGER_SET", "Employee", id.toString(), actorId);
        return employee;
    }

    // --- work patterns -----------------------------------------------------

    @Transactional(readOnly = true)
    public List<WorkPattern> listWorkPatterns() {
        return patterns.findByTenantIdOrderByNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public WorkPattern workPattern(UUID id) {
        return patterns.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new WorkPatternNotFoundException(id));
    }

    @Transactional
    public WorkPattern createWorkPattern(String code, String name, BigDecimal[] week,
                                         UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A work pattern needs a name");
        }
        if (week == null || week.length != 7) {
            throw new IllegalArgumentException(
                    "A work pattern needs a fraction for each of the seven days");
        }
        String normalized = WorkPattern.normalizeCode(code);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A work pattern code is required");
        }
        if (patterns.findByTenantIdAndCode(tenantId, normalized).isPresent()) {
            throw new ConflictException("Work pattern code already in use: " + normalized);
        }

        WorkPattern pattern = new WorkPattern(normalized, name);
        pattern.setWeek(week[0], week[1], week[2], week[3], week[4], week[5], week[6]);

        WorkPattern saved = patterns.save(pattern);
        audit.recordSuccess("WORK_PATTERN_CREATED", "WorkPattern",
                saved.getId().toString(), actorId);
        return saved;
    }

    /**
     * Puts somebody on a work pattern, or back on the default week.
     *
     * <p>Changing this changes what future leave costs them and what they accrue. It does not
     * touch leave already requested: those requests stored the days they were charged, and
     * rewriting history because a contract changed today would be worse than the inconsistency.
     */
    @Transactional
    public Employee setWorkPattern(UUID id, UUID workPatternId, UUID actorId) {
        Employee employee = get(id);
        employee.setWorkPattern(workPatternId == null ? null : workPattern(workPatternId));
        audit.recordSuccess("EMPLOYEE_WORK_PATTERN_SET", "Employee", id.toString(), actorId);
        return employee;
    }

    /** Links a login to this employee, so self-service can find the right record. */
    @Transactional
    public Employee linkUserAccount(UUID id, UUID userAccountId, UUID actorId) {
        Employee employee = get(id);
        employee.linkUserAccount(userAccountId);
        audit.recordSuccess("EMPLOYEE_ACCOUNT_LINKED", "Employee", id.toString(), actorId);
        return employee;
    }

    @Transactional
    public Employee changeStatus(UUID id, EmployeeStatus status, UUID actorId) {
        Employee employee = get(id);
        employee.changeStatus(status);
        audit.recordSuccess("EMPLOYEE_STATUS_CHANGED", "Employee", id.toString(), actorId);
        return employee;
    }

    /**
     * Ends someone's employment, closing their running contract in the same step.
     *
     * <p>Leaving a contract active behind a terminated employee is the kind of inconsistency that
     * surfaces months later in a payroll run.
     */
    @Transactional
    public Employee terminate(UUID id, LocalDate terminationDate, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = get(id);
        employee.terminate(terminationDate);

        contracts.findByTenantIdAndEmployeeIdAndStatus(tenantId, id, ContractStatus.ACTIVE)
                .ifPresent(EmploymentContract::terminate);

        audit.recordSuccess("EMPLOYEE_TERMINATED", "Employee", id.toString(), actorId);
        return employee;
    }

    public static class EmployeeNotFoundException extends ResourceNotFoundException {
        public EmployeeNotFoundException(UUID id) {
            super("Employee not found: " + id);
        }
    }

    public static class WorkPatternNotFoundException extends ResourceNotFoundException {
        public WorkPatternNotFoundException(UUID id) {
            super("Work pattern not found: " + id);
        }
    }

    public static class EmployeeNumberAlreadyUsedException extends ConflictException {
        public EmployeeNumberAlreadyUsedException(String number) {
            super("Employee number already in use: " + number);
        }
    }
}
