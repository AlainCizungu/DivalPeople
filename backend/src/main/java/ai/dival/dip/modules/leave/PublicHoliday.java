package ai.dival.dip.modules.leave;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A day the office is closed.
 *
 * <p>Per tenant rather than global: a platform serving several countries cannot assume one
 * calendar, and a regional holiday that only some offices observe is normal.
 */
@Entity
@Table(name = "public_holiday")
public class PublicHoliday extends TenantOwnedEntity {

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected PublicHoliday() {
        // for JPA
    }

    public PublicHoliday(LocalDate holidayDate, String name) {
        if (holidayDate == null) {
            throw new IllegalArgumentException("A holiday needs a date");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A holiday needs a name");
        }
        this.holidayDate = holidayDate;
        this.name = name.trim();
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public String getName() {
        return name;
    }
}
