package ai.dival.dip.modules.leave;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, UUID> {

    List<LeaveType> findByTenantIdOrderByNameAsc(UUID tenantId);

    List<LeaveType> findByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);

    Optional<LeaveType> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<LeaveType> findByTenantIdAndCode(UUID tenantId, String code);
}
