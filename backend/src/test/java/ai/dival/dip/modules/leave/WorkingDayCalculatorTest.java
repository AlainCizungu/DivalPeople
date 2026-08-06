package ai.dival.dip.modules.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The arithmetic people check.
 *
 * <p>June 2026 is used throughout: the 1st is a Monday, which makes the weeks easy to reason
 * about when reading these expectations.
 */
@Transactional
@RequiresDocker
class WorkingDayCalculatorTest extends AbstractIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 6, 5);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 6, 6);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 6, 7);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private WorkingDayCalculator calculator;
    @Autowired
    private PublicHolidayService holidays;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("W A", "w-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private BigDecimal count(LocalDate from, LocalDate to) {
        return calculator.countDays(from, to, false, false);
    }

    @Test
    @DisplayName("a single working day is one day")
    void singleDay() {
        assertThat(count(MONDAY, MONDAY)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Monday to Friday is five days")
    void fullWeek() {
        assertThat(count(MONDAY, FRIDAY)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a weekend inside the range is not charged")
    void skipsWeekend() {
        // Monday the 1st to Friday the 12th spans a weekend: ten working days, not twelve.
        assertThat(count(MONDAY, LocalDate.of(2026, 6, 12))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("a weekend on its own costs nothing")
    void weekendOnlyIsFree() {
        assertThat(count(SATURDAY, SUNDAY)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a public holiday inside the range is not charged")
    void skipsPublicHoliday() {
        holidays.add(LocalDate.of(2026, 6, 3), "Founders' Day", null);

        assertThat(count(MONDAY, FRIDAY)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("half a day at the start takes off half a day")
    void halfDayStart() {
        assertThat(calculator.countDays(MONDAY, FRIDAY, true, false))
                .isEqualByComparingTo("4.5");
    }

    @Test
    @DisplayName("half days at both ends take off a whole day between them")
    void halfDayBothEnds() {
        assertThat(calculator.countDays(MONDAY, FRIDAY, true, true))
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a single day taken as a half day is half a day, not zero")
    void singleHalfDay() {
        assertThat(calculator.countDays(MONDAY, MONDAY, true, true))
                .isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("a half day marked on a weekend takes nothing off")
    void halfDayOnNonWorkingDayIsIgnored() {
        // Saturday to Wednesday: three working days. Marking the Saturday as a half day must not
        // subtract half a day that was never charged in the first place.
        assertThat(calculator.countDays(SATURDAY, LocalDate.of(2026, 6, 10), true, false))
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("a half day on a public holiday takes nothing off either")
    void halfDayOnHolidayIsIgnored() {
        holidays.add(MONDAY, "Founders' Day", null);

        assertThat(calculator.countDays(MONDAY, FRIDAY, true, false))
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("holidays are per tenant, so one tenant's calendar does not shorten another's")
    void holidaysDoNotCrossTenants() {
        holidays.add(LocalDate.of(2026, 6, 3), "Founders' Day", null);
        assertThat(count(MONDAY, FRIDAY)).isEqualByComparingTo("4");

        UUID tenantB = tenants.save(new Tenant("W B", "w-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantB);

        assertThat(count(MONDAY, FRIDAY)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("leave cannot end before it starts")
    void refusesReversedRange() {
        assertThatThrownBy(() -> count(FRIDAY, MONDAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the same day cannot be recorded as a holiday twice")
    void refusesDuplicateHoliday() {
        holidays.add(MONDAY, "Founders' Day", null);

        assertThatThrownBy(() -> holidays.add(MONDAY, "Founders Day", null))
                .isInstanceOf(ai.dival.dip.common.error.ConflictException.class);
    }
}
