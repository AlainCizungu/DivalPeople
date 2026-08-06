package ai.dival.dip.modules.payroll;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * One person's pay for one period.
 *
 * <p>A snapshot. Employee number, name and base salary are copied at calculation rather than read
 * live, because a payslip must say what it said the day it was issued whatever has changed about
 * the person since.
 *
 * <p>The totals are never computed independently. {@link #addLine} is the only way a figure gets
 * onto a payslip, and it re-totals from the lines every time, which is what makes the document
 * reconcile by construction rather than by hope. The database enforces the same rule.
 */
@Entity
@Table(name = "payslip")
public class Payslip extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false)
    private PayrollPeriod period;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "employee_number", nullable = false, length = 50)
    private String employeeNumber;

    @Column(name = "employee_name", nullable = false, length = 300)
    private String employeeName;

    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "gross_earnings", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossEarnings = BigDecimal.ZERO;

    @Column(name = "total_deductions", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDeductions = BigDecimal.ZERO;

    @Column(name = "employer_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal employerCost = BigDecimal.ZERO;

    @Column(name = "net_pay", nullable = false, precision = 18, scale = 2)
    private BigDecimal netPay = BigDecimal.ZERO;

    /** Carried from leave and attendance, so the figures behind a deduction are on the document. */
    @Column(name = "unpaid_leave_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal unpaidLeaveDays = BigDecimal.ZERO;

    @Column(name = "absent_minutes", nullable = false)
    private int absentMinutes;

    @Column(name = "overtime_minutes", nullable = false)
    private int overtimeMinutes;

    @OneToMany(mappedBy = "payslip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<PayslipLine> lines = new ArrayList<>();

    protected Payslip() {
        // for JPA
    }

    public Payslip(PayrollPeriod period, Employee employee, BigDecimal baseAmount,
                   String currency) {
        this.period = period;
        this.employee = employee;
        // Snapshotted, not read live.
        this.employeeNumber = employee.getEmployeeNumber();
        this.employeeName = employee.displayName();
        this.baseAmount = baseAmount.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency;
    }

    /** Records what leave and attendance said about this period, for the lines that use it. */
    public void setInputs(BigDecimal unpaidLeaveDays, int absentMinutes, int overtimeMinutes) {
        requireOpen();
        this.unpaidLeaveDays = unpaidLeaveDays == null
                ? BigDecimal.ZERO
                : unpaidLeaveDays.setScale(2, RoundingMode.HALF_UP);
        this.absentMinutes = Math.max(0, absentMinutes);
        this.overtimeMinutes = Math.max(0, overtimeMinutes);
    }

    /**
     * Adds a line and re-totals.
     *
     * <p>The only way an amount reaches a payslip. Nothing else may set gross, deductions or net,
     * which is the whole reason the document reconciles.
     */
    public PayslipLine addLine(String componentCode, String componentName, ComponentType type,
                               String basis, BigDecimal quantity, BigDecimal rate,
                               BigDecimal amount, int sortOrder) {
        requireOpen();
        if (amount == null || amount.signum() < 0) {
            // A negative earning is a deduction and a negative deduction is an earning. Allowing
            // either would make the type on the line a lie.
            throw new IllegalArgumentException(
                    "A line amount cannot be negative; use the other component type");
        }

        PayslipLine line = new PayslipLine(this, componentCode, componentName, type, basis,
                quantity, rate, amount.setScale(2, RoundingMode.HALF_UP), sortOrder);
        lines.add(line);
        retotal();
        return line;
    }

    /** Taxable gross so far, for a component that takes a percentage of it. */
    public BigDecimal taxableGrossSoFar() {
        return lines.stream()
                .filter(line -> line.getComponentType().addsToGross())
                .map(PayslipLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void retotal() {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal employer = BigDecimal.ZERO;

        for (PayslipLine line : lines) {
            switch (line.getComponentType()) {
                case EARNING -> gross = gross.add(line.getAmount());
                case DEDUCTION -> deductions = deductions.add(line.getAmount());
                case EMPLOYER_CONTRIBUTION -> employer = employer.add(line.getAmount());
            }
        }

        this.grossEarnings = gross;
        this.totalDeductions = deductions;
        this.employerCost = employer;
        this.netPay = gross.subtract(deductions);
    }

    private void requireOpen() {
        if (period.getStatus().isFrozen()) {
            throw new ConflictException(
                    "This payroll period has been approved; reopen it to change anything");
        }
    }

    public PayrollPeriod getPeriod() {
        return period;
    }

    public Employee getEmployee() {
        return employee;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getGrossEarnings() {
        return grossEarnings;
    }

    public BigDecimal getTotalDeductions() {
        return totalDeductions;
    }

    public BigDecimal getEmployerCost() {
        return employerCost;
    }

    public BigDecimal getNetPay() {
        return netPay;
    }

    public BigDecimal getUnpaidLeaveDays() {
        return unpaidLeaveDays;
    }

    public int getAbsentMinutes() {
        return absentMinutes;
    }

    public int getOvertimeMinutes() {
        return overtimeMinutes;
    }

    public List<PayslipLine> getLines() {
        return List.copyOf(lines);
    }
}
