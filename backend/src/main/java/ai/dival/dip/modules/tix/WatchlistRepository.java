package ai.dival.dip.modules.tix;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The next slice of a sweep: live watches, least recently checked first.
     *
     * <p><strong>Ordered by last check, and that ordering is the whole design of a sweep that
     * cannot finish.</strong> Every check is a real inquiry against the hourly allowance, so a
     * watchlist of twelve thousand subjects takes many nights at a hundred and twenty an hour.
     * Taking the stalest rows each night means every subject is reached in turn and none is starved
     * — whereas any fixed ordering would sweep the first N for ever and never look at the rest.
     *
     * <p>Nulls first: a watch that has never been checked has no last answer to differ from, and
     * getting its baseline is more urgent than re-checking one whose baseline exists.
     */
    @Query("select e from WatchlistEntry e join fetch e.subject "
            + "where e.tenantId = :tenantId and e.expiresAt > :now "
            + "order by e.lastCheckedAt asc nulls first")
    List<WatchlistEntry> findDueForSweep(@Param("tenantId") UUID tenantId,
                                         @Param("now") Instant now, Limit limit);

    /** Subjects in one group, for the count beside its name. */
    long countByTenantIdAndWatchlistId(UUID tenantId, UUID watchlistId);

    List<WatchlistEntry> findByTenantIdAndWatchlistIdOrderByExpiresAt(UUID tenantId,
                                                                     UUID watchlistId);

    /** The ones nobody has filed. Distinct from an empty group, and the screen says so. */
    List<WatchlistEntry> findByTenantIdAndWatchlistIsNullOrderByExpiresAt(UUID tenantId);

    long countByTenantIdAndWatchlistIsNull(UUID tenantId);
}
