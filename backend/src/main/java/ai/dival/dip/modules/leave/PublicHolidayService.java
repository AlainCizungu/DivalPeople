package ai.dival.dip.modules.leave;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The days the office is closed.
 *
 * <p>Adding a holiday does not retrospectively change leave already requested: requests store the
 * days they were charged at submission. Declaring a public holiday in the middle of somebody's
 * approved fortnight is a correction for a human to make, not something to apply silently.
 */
@Service
public class PublicHolidayService {

    private final PublicHolidayRepository holidays;
    private final AuditService audit;

    public PublicHolidayService(PublicHolidayRepository holidays, AuditService audit) {
        this.holidays = holidays;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PublicHoliday> list() {
        return holidays.findByTenantIdOrderByHolidayDateAsc(TenantContext.require());
    }

    @Transactional
    public PublicHoliday add(LocalDate date, String name, UUID actorId) {
        UUID tenantId = TenantContext.require();

        holidays.findByTenantIdAndHolidayDate(tenantId, date).ifPresent(existing -> {
            throw new ConflictException(
                    date + " is already recorded as " + existing.getName());
        });

        PublicHoliday saved = holidays.save(new PublicHoliday(date, name));
        audit.recordSuccess("HOLIDAY_ADDED", "PublicHoliday", saved.getId().toString(), actorId);
        return saved;
    }
}
