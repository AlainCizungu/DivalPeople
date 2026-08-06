package ai.dival.dip.modules.recruitment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRequisitionRepository extends JpaRepository<JobRequisition, UUID> {

    List<JobRequisition> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<JobRequisition> findByTenantIdAndStatus(UUID tenantId, RequisitionStatus status);

    Optional<JobRequisition> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<JobRequisition> findByTenantIdAndRequisitionNumber(UUID tenantId, String number);
}
