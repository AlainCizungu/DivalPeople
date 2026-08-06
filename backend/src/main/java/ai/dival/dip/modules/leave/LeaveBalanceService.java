package ai.dival.dip.modules.leave;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.EmployeeService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave types, entitlements and the balances they produce.
 *
 * <p>Every movement writes a ledger entry alongside the running total. The total is what the
 * overdraft check reads; the ledger is why it says what it says. They are only ever changed
 * together, through {@link #record}, so the two cannot drift.
 */
@Service
public class LeaveBalanceService {

    private final LeaveTypeRepository types;
    private final LeaveBalanceRepository balances;
    private final LeaveLedgerEntryRepository ledger;
    private final EmployeeService employees;
    private final AuditService audit;

    public LeaveBalanceService(LeaveTypeRepository types, LeaveBalanceRepository balances,
                               LeaveLedgerEntryRepository ledger, EmployeeService employees,
                               AuditService audit) {
        this.types = types;
        this.balances = balances;
        this.ledger = ledger;
        this.employees = employees;
        this.audit = audit;
    }

    // --- leave types -------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LeaveType> listTypes() {
        return types.findByTenantIdOrderByNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public List<LeaveType> activeTypes() {
        return types.findByTenantIdAndActiveTrueOrderByNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public LeaveType type(UUID id) {
        return types.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new LeaveTypeNotFoundException(id));
    }

    @Transactional
    public LeaveType createType(String code, String name, BigDecimal entitlementDays,
                                AccrualMethod accrualMethod, UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A leave type needs a name");
        }
        if (entitlementDays != null && entitlementDays.signum() < 0) {
            throw new IllegalArgumentException("An entitlement cannot be negative");
        }
        String normalized = LeaveType.normalizeCode(code);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A leave type code is required");
        }
        if (types.findByTenantIdAndCode(tenantId, normalized).isPresent()) {
            throw new ConflictException("Leave type code already in use: " + normalized);
        }

        LeaveType saved = types.save(
                new LeaveType(normalized, name, entitlementDays, accrualMethod));
        audit.recordSuccess("LEAVE_TYPE_CREATED", "LeaveType", saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public LeaveType retireType(UUID id, UUID actorId) {
        LeaveType type = type(id);
        type.retire();
        audit.recordSuccess("LEAVE_TYPE_RETIRED", "LeaveType", id.toString(), actorId);
        return type;
    }

    // --- balances ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LeaveBalance> balancesFor(UUID employeeId, int leaveYear) {
        employees.get(employeeId);
        return balances.findByTenantIdAndEmployeeIdAndLeaveYear(
                TenantContext.require(), employeeId, leaveYear);
    }

    @Transactional(readOnly = true)
    public LeaveBalance balance(UUID id) {
        return balances.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new BalanceNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<LeaveLedgerEntry> ledgerFor(UUID balanceId) {
        balance(balanceId);
        return ledger.findByTenantIdAndBalanceIdOrderByCreatedAtAsc(
                TenantContext.require(), balanceId);
    }

    /**
     * The balance row for a person, year and type, creating it if this is their first movement.
     *
     * <p>Taken with a write lock, because everything that spends days goes through here. Two
     * requests submitted in the same instant queue rather than both reading the same remainder.
     */
    @Transactional
    public LeaveBalance balanceFor(UUID employeeId, UUID leaveTypeId, int leaveYear) {
        UUID tenantId = TenantContext.require();
        return balances.lockFor(tenantId, employeeId, leaveTypeId, leaveYear)
                .orElseGet(() -> balances.save(
                        new LeaveBalance(employees.get(employeeId), type(leaveTypeId), leaveYear)));
    }

    /**
     * Moves a balance and explains the movement in one step.
     *
     * <p>Package-private so nothing outside this module can change a total without leaving a
     * ledger entry behind. That is the whole guarantee.
     */
    LeaveLedgerEntry record(LeaveBalance balance, LedgerEntryType type, BigDecimal days,
                            LeaveRequest request, String reason, UUID actorId) {
        return ledger.save(
                new LeaveLedgerEntry(balance, type, days, request, reason, actorId));
    }

    /**
     * Grants or accrues an entitlement.
     *
     * <p>Idempotent by month for accrual: running the job twice in a day must not pay somebody
     * twice, and jobs get run twice.
     */
    @Transactional
    public LeaveBalance grant(UUID employeeId, UUID leaveTypeId, int leaveYear, BigDecimal days,
                              LedgerEntryType entryType, String reason, UUID actorId) {
        if (days == null || days.signum() <= 0) {
            throw new IllegalArgumentException("A grant must be a positive number of days");
        }
        LeaveBalance balance = balanceFor(employeeId, leaveTypeId, leaveYear);
        balance.credit(days, entryType);
        record(balance, entryType, days, null, reason, actorId);
        audit.recordSuccess("LEAVE_" + entryType, "LeaveBalance",
                balance.getId().toString(), actorId);
        return balance;
    }

    /**
     * A manual correction. Signed: negative takes days away.
     *
     * <p>Requires a reason. An adjustment nobody can explain is the entry that gets challenged
     * first, and the person making it will not be in the room.
     */
    @Transactional
    public LeaveBalance adjust(UUID employeeId, UUID leaveTypeId, int leaveYear, BigDecimal days,
                               String reason, UUID actorId) {
        if (days == null || days.signum() == 0) {
            throw new IllegalArgumentException("An adjustment of zero days is not an adjustment");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An adjustment needs a reason");
        }

        LeaveBalance balance = balanceFor(employeeId, leaveTypeId, leaveYear);
        balance.credit(days, LedgerEntryType.ADJUSTMENT);
        record(balance, LedgerEntryType.ADJUSTMENT, days, null, reason, actorId);
        audit.recordSuccess("LEAVE_ADJUSTED", "LeaveBalance", balance.getId().toString(), actorId);
        return balance;
    }

    /**
     * Opens next year: carries the remainder forward, capped, and lapses the rest.
     *
     * <p>The lapse is written as its own entry rather than silently dropped. Somebody who loses
     * six days at year end is entitled to see that it happened, and when.
     */
    @Transactional
    public LeaveBalance carryOver(UUID employeeId, UUID leaveTypeId, int fromYear, UUID actorId) {
        UUID tenantId = TenantContext.require();
        LeaveBalance closing = balances
                .findByTenantIdAndEmployeeIdAndLeaveTypeIdAndLeaveYear(
                        tenantId, employeeId, leaveTypeId, fromYear)
                .orElseThrow(() -> new ConflictException(
                        "No balance to carry over from " + fromYear));

        if (closing.getPendingDays().signum() != 0) {
            throw new ConflictException(
                    "Requests are still awaiting a decision in " + fromYear);
        }

        LeaveType type = closing.getLeaveType();
        BigDecimal remainder = closing.entitled().max(BigDecimal.ZERO);
        BigDecimal carried = remainder.min(type.getCarryoverMaxDays());
        BigDecimal lapsed = remainder.subtract(carried);

        LeaveBalance opening = balanceFor(employeeId, leaveTypeId, fromYear + 1);
        if (opening.getOpeningDays().signum() != 0) {
            throw new ConflictException("Carryover has already been applied");
        }

        if (lapsed.signum() > 0) {
            closing.credit(lapsed.negate(), LedgerEntryType.LAPSED);
            record(closing, LedgerEntryType.LAPSED, lapsed.negate(), null,
                    "Above the " + type.getCarryoverMaxDays() + " day carryover cap", actorId);
        }
        if (carried.signum() > 0) {
            opening.credit(carried, LedgerEntryType.OPENING);
            record(opening, LedgerEntryType.OPENING, carried, null,
                    "Carried over from " + fromYear, actorId);
        }

        audit.recordSuccess("LEAVE_CARRIED_OVER", "LeaveBalance",
                opening.getId().toString(), actorId);
        return opening;
    }

    public static class LeaveTypeNotFoundException extends ResourceNotFoundException {
        public LeaveTypeNotFoundException(UUID id) {
            super("Leave type not found: " + id);
        }
    }

    public static class BalanceNotFoundException extends ResourceNotFoundException {
        public BalanceNotFoundException(UUID id) {
            super("Leave balance not found: " + id);
        }
    }
}
