package ai.dival.dip.modules.ingest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawRecordRepository extends JpaRepository<RawRecord, UUID> {

    List<RawRecord> findByBatchIdOrderByRowNumberAsc(UUID batchId);

    Optional<RawRecord> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByBatchId(UUID batchId);
}
