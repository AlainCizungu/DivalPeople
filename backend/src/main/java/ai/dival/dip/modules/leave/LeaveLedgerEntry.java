package ai.dival.dip.modules.leave;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One movement in a leave balance.
 *
 * <p>Append-only: there are no setters, and the migration grants no UPDATE. A correction is a
 * further entry, never an edit. A leave balance that can be quietly rewritten is worth nothing
 * the day somebody disputes it.
 */
@Entity
@Table(name = "leave_ledger_entry")
public class LeaveLedgerEntry extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "balance_id", nullable = false)
    private LeaveBalance balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private LedgerEntryType entryType;

    /** Signed: positive adds to what somebody has, negative spends it. */
    @Column(name = "days", nullable = false, precision = 6, scale = 2)
    private BigDecimal days;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private LeaveRequest request;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "actor_id")
    private UUID actorId;

    protected LeaveLedgerEntry() {
        // for JPA
    }

    public LeaveLedgerEntry(LeaveBalance balance, LedgerEntryType entryType, BigDecimal days,
                            LeaveRequest request, String reason, UUID actorId) {
        if (days == null || days.signum() == 0) {
            throw new IllegalArgumentException("A ledger entry that moves nothing is not an entry");
        }
        this.balance = balance;
        this.entryType = entryType;
        this.days = days;
        this.request = request;
        this.reason = reason;
        this.actorId = actorId;
    }

    public LeaveBalance getBalance() {
        return balance;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getDays() {
        return days;
    }

    public LeaveRequest getRequest() {
        return request;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorId() {
        return actorId;
    }
}
