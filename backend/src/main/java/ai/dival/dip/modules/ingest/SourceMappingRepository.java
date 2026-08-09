package ai.dival.dip.modules.ingest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceMappingRepository extends JpaRepository<SourceMapping, UUID> {

    /**
     * The mapping in force for a source.
     *
     * <p>The partial unique index in V25 is what makes "the" correct rather than "a": at most one
     * row per source has a null supersession stamp, and the database enforces that rather than
     * whoever remembers to stamp the old one.
     */
    Optional<SourceMapping> findByTenantIdAndDataSourceIdAndSupersededAtIsNull(
            UUID tenantId, UUID dataSourceId);

    /** Every version, newest first. A mapping's history is how a published batch is explained. */
    List<SourceMapping> findByTenantIdAndDataSourceIdOrderByVersionNumberDesc(
            UUID tenantId, UUID dataSourceId);
}
