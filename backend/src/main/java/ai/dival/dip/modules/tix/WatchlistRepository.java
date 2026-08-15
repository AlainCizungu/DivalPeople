package ai.dival.dip.modules.tix;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Watches, which are tenant-owned and stay that way.
 *
 * <p>No cross-operator query exists here and none should. Reading across would tell one
 * participant which companies a rival is worried about, and that is a commercial intention rather
 * than a fact about a debtor — quite different from the debt records the exchange is for.
 */
public interface WatchlistRepository extends JpaRepository<WatchlistEntry, UUID> {

    List<WatchlistEntry> findByTenantIdOrderByExpiresAt(UUID tenantId);

    Optional<WatchlistEntry> findByTenantIdAndSubjectId(UUID tenantId, UUID subjectId);

    Optional<WatchlistEntry> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Live watches for one operator, which is what a sweep runs over. */
    List<WatchlistEntry> findByTenantIdAndExpiresAtAfter(UUID tenantId, Instant now);

    long countByTenantIdAndExpiresAtAfter(UUID tenantId, Instant now);
}
