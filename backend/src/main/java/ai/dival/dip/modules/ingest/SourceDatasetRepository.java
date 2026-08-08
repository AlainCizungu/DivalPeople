package ai.dival.dip.modules.ingest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceDatasetRepository extends JpaRepository<SourceDataset, UUID> {

    List<SourceDataset> findByTenantIdOrderByNameAsc(UUID tenantId);

    Optional<SourceDataset> findByTenantIdAndCode(UUID tenantId, String code);

    Optional<SourceDataset> findByIdAndTenantId(UUID id, UUID tenantId);
}
