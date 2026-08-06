package ai.dival.dip.modules.leave;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicHolidayRepository extends JpaRepository<PublicHoliday, UUID> {

    List<PublicHoliday> findByTenantIdAndHolidayDateBetween(
            UUID tenantId, LocalDate from, LocalDate to);

    List<PublicHoliday> findByTenantIdOrderByHolidayDateAsc(UUID tenantId);

    Optional<PublicHoliday> findByTenantIdAndHolidayDate(UUID tenantId, LocalDate holidayDate);
}
