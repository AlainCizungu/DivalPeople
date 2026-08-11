package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRequestRepository extends JpaRepository<SubjectRequest, UUID> {

    List<SubjectRequest> findByTenantIdOrderByRaisedAtDesc(UUID tenantId);

    Optional<SubjectRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Cases still waiting on somebody here.
     *
     * <p>Counted in the database rather than by loading the queue and filtering it. The overview
     * screen showed four figures derived from every record the operator had ever declared, fetched
     * into the browser — which was fine at a dozen records and is 3,699 after one real import.
     *
     * <p>"Open" is the absence of a decision rather than a list of statuses, so a status added
     * later is open until somebody says otherwise. That is the safer default for a queue with
     * statutory deadlines: a case nobody has classified should nag, not hide.
     */
    @Query("select count(r) from SubjectRequest r where r.tenantId = :tenantId "
            + "and r.status not in (ai.dival.dip.modules.tix.SubjectRequestStatus.UPHELD, "
            + "ai.dival.dip.modules.tix.SubjectRequestStatus.REFUSED, "
            + "ai.dival.dip.modules.tix.SubjectRequestStatus.WITHDRAWN)")
    long countOpen(@Param("tenantId") UUID tenantId);

    /** Open and past the deadline the Code sets. Missing one is itself grounds for a complaint. */
    @Query("select count(r) from SubjectRequest r where r.tenantId = :tenantId "
            + "and r.dueAt < :now "
            + "and r.status not in (ai.dival.dip.modules.tix.SubjectRequestStatus.UPHELD, "
            + "ai.dival.dip.modules.tix.SubjectRequestStatus.REFUSED, "
            + "ai.dival.dip.modules.tix.SubjectRequestStatus.WITHDRAWN)")
    long countOverdue(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    /** Open, not yet late, and due inside the window. The ones worth doing today. */
    @Query("select count(r) from SubjectRequest r where r.tenantId = :tenantId "
            + "and r.dueAt >= :now and r.dueAt < :until "
            + "and r.status not in (ai.dival.dip.modules.tix.SubjectRequestStatus.UPHELD, "
            + "ai.dival.dip.modules.tix.SubjectRequestStatus.REFUSED, "
            + "ai.dival.dip.modules.tix.SubjectRequestStatus.WITHDRAWN)")
    long countDueBefore(@Param("tenantId") UUID tenantId,
                        @Param("now") Instant now, @Param("until") Instant until);
}
