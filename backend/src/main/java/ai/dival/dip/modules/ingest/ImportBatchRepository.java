package ai.dival.dip.modules.ingest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    List<ImportBatch> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);

    Optional<ImportBatch> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Whether this exact file is already live for this operator.
     *
     * <p>Checked before publishing so a duplicate upload gets a sentence it can act on rather
     * than a constraint violation at flush time. The partial unique index in V20 is the real
     * guarantee and stays — this is the readable half of the same rule.
     */
    Optional<ImportBatch> findByTenantIdAndChecksumSha256AndStatus(
            UUID tenantId, String checksumSha256, BatchStatus status);
}
