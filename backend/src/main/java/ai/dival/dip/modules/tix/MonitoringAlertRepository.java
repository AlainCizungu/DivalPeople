package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Alerts, which belong to the operator that was watching and to nobody else.
 *
 * <p>Every read is join-fetched on the subject. The queue names the company on every row, the
 * mapping happens after the transaction closes, and a lazy proxy there is a 500 on the one screen
 * this feature exists to produce.
 */
public interface MonitoringAlertRepository extends JpaRepository<MonitoringAlert, UUID> {

    /**
     * Open alerts, newest first.
     *
     * <p><strong>Not ordered by severity here, deliberately.</strong> The column stores the enum
     * name, so {@code order by severity} sorts alphabetically — INFORMATIONAL, MATERIAL, NOTABLE —
     * which puts the least urgent at the top of a queue whose entire job is the opposite, and it
     * would have looked like a working sort. A CASE expression over enum literals would fix it and
     * is the kind of JPQL that differs between providers; the service sorts instead, where the
     * ordering is plain Java and can be tested without a database.
     *
     * <p>Bounded by {@code limit} so that an operator with a large watchlist and a bad month does
     * not pull ten thousand rows into memory to render a screen that shows fifty.
     */
    @Query("select a from MonitoringAlert a join fetch a.subject "
            + "where a.tenantId = :tenantId and a.acknowledgedAt is null "
            + "order by a.raisedAt desc")
    List<MonitoringAlert> findOpen(@Param("tenantId") UUID tenantId, Limit limit);

    /** One company's history, acknowledged or not. */
    @Query("select a from MonitoringAlert a join fetch a.subject "
            + "where a.tenantId = :tenantId and a.subject.id = :subjectId "
            + "order by a.raisedAt desc")
    List<MonitoringAlert> findForSubject(@Param("tenantId") UUID tenantId,
                                         @Param("subjectId") UUID subjectId);

    java.util.Optional<MonitoringAlert> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantIdAndAcknowledgedAtIsNull(UUID tenantId);
}
