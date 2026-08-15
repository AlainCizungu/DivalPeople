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

    /**
     * How many times one action was recorded, per month, since a moment. Oldest first.
     *
     * <p>The only history the platform has. Nothing snapshots exposure or record counts, so a
     * chart of "how much was owed last March" cannot be drawn honestly and is not drawn; what
     * <em>is</em> recorded, every time, is that something happened and who did it. Counting those
     * rows is therefore the one trend line that rests on evidence rather than on reconstruction.
     */
    @Query(value = "select to_char(date_trunc('month', occurred_at at time zone 'UTC'), "
            + "'YYYY-MM') as month, count(*) as total "
            + "from audit_event where tenant_id = :tenantId and action = :action "
            + "and outcome = :outcome and occurred_at >= :since group by 1 order by 1",
            nativeQuery = true)
    List<Object[]> countByMonth(@Param("tenantId") UUID tenantId, @Param("action") String action,
                                @Param("outcome") String outcome, @Param("since") Instant since);

    /** How many of each action a tenant has recorded, most frequent first. */
    /**
     * How each of an operator's own users has been using the exchange, since a moment.
     *
     * <p>Four numbers per person: how many inquiries, how many resolved to nobody, how many the
     * rate limiter refused, and when they last asked. That is enough to tell somebody doing their
     * job from somebody walking an identifier space, and it is the only place in the platform
     * where the question can be asked at all — the audit trail was built to make a sweep legible
     * after the fact, and nothing has ever read it back.
     *
     * <p>A null resource id is the tell. The exchange records the subject when it confirms a match
     * and null when it does not, so a caller guessing identifiers produces a long row of nulls.
     */
    @Query("select e.actorId, count(e), "
            + "sum(case when e.resourceId is null then 1 else 0 end), "
            + "sum(case when e.outcome = :denied then 1 else 0 end), "
            + "max(e.occurredAt) "
            + "from AuditEvent e "
            + "where e.tenantId = :tenantId and e.action = :action and e.occurredAt >= :since "
            + "group by e.actorId")
    List<Object[]> inquiryBehaviourSince(@Param("tenantId") UUID tenantId,
                                         @Param("action") String action,
                                         @Param("denied") String denied,
                                         @Param("since") java.time.Instant since);

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
