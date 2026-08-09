package ai.dival.dip.common.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId);

    List<AuditEvent> findByTenantIdAndResourceTypeOrderByOccurredAtDesc(UUID tenantId, String resourceType);

    /**
     * The most recent events for one tenant, newest first, optionally narrowed to one action.
     *
     * <p>Paged rather than unbounded. The existing {@code findByTenantId…} returns everything,
     * which is fine for a test and not for a screen: an operator that has imported the real export
     * a few times has tens of thousands of rows, and a trail nobody can load is a trail nobody
     * reads.
     *
     * <p>{@code :action} is null to mean "all", which keeps this one query rather than two and
     * keeps the ordering identical between them — a filtered view that sorted differently from the
     * unfiltered one would be quietly misleading about what happened when.
     */
    @Query("select e from AuditEvent e where e.tenantId = :tenantId "
            + "and (:action is null or e.action = :action) "
            + "order by e.occurredAt desc")
    List<AuditEvent> findRecent(@Param("tenantId") UUID tenantId,
                                @Param("action") String action,
                                Pageable page);

    /** How many of each action a tenant has recorded, most frequent first. */
    @Query("select e.action, count(e) from AuditEvent e where e.tenantId = :tenantId "
            + "group by e.action order by count(e) desc")
    List<Object[]> countByAction(@Param("tenantId") UUID tenantId);

    /**
     * Who, inside one tenant, successfully did something to a given resource since a moment.
     *
     * <p>Written for article 214 of the Code du numérique, which requires that a correction or an
     * erasure be communicated not only to the person but « aux personnes à qui les données
     * inexactes […] ont été communiquées ». The audit trail is the only place that list exists.
     *
     * <p>Deliberately generic — action, resource type and id are parameters — because the audit
     * module has no business knowing what TIX is. Tenant-scoped like everything else here; a
     * caller wanting the whole picture binds each tenant in turn.
     *
     * <p>Null actors are excluded rather than returned as nulls to be filtered later. An event
     * that names nobody cannot be notified, and the count of those is worth measuring separately
     * rather than losing in a stream.
     */
    @Query("select distinct e.actorId from AuditEvent e "
            + "where e.tenantId = :tenantId and e.action = :action "
            + "and e.resourceType = :resourceType and e.resourceId = :resourceId "
            + "and e.outcome = :outcome and e.occurredAt >= :since and e.actorId is not null")
    List<UUID> findDistinctActors(@Param("tenantId") UUID tenantId,
                                  @Param("action") String action,
                                  @Param("resourceType") String resourceType,
                                  @Param("resourceId") String resourceId,
                                  @Param("outcome") String outcome,
                                  @Param("since") Instant since);
}
