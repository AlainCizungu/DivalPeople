package ai.dival.dip.common.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId);

    List<AuditEvent> findByTenantIdAndResourceTypeOrderByOccurredAtDesc(UUID tenantId, String resourceType);

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
