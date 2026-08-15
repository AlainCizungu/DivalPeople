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

    /**
     * Cases decided on or before the deadline they carried.
     *
     * <p>Compared against {@code dueAt}, which is the deadline written when the case was raised
     * rather than the one in force today. That is the only comparison that means anything: the
     * periods moved from 60/30 to 10/20 in August 2026, and judging last year's cases by this
     * year's rule would manufacture a compliance failure that never happened.
     *
     * <p>Two queries rather than one with a boolean flag. The single version read
     * {@code (:late = true and ...) or (:late = false and ...)}, which asks Hibernate to infer a
     * parameter's type from a comparison against a literal — and buys, in exchange for that risk,
     * a method whose meaning depends on an argument the caller has to read the javadoc to
     * understand. Two names say it instead.
     */
    @Query("select count(r) from SubjectRequest r where r.tenantId = :tenantId "
            + "and r.decidedAt is not null and r.decidedAt <= r.dueAt")
    long countDecidedInTime(@Param("tenantId") UUID tenantId);

    /**
     * Cases decided after the deadline they carried.
     *
     * <p>Article 214 makes a missed deadline grounds in itself for a complaint, so this is not a
     * service level — it counts occasions on which somebody could have complained and been right.
     */
    @Query("select count(r) from SubjectRequest r where r.tenantId = :tenantId "
            + "and r.decidedAt is not null and r.decidedAt > r.dueAt")
    long countDecidedLate(@Param("tenantId") UUID tenantId);

    /** Everything ever raised here, however it ended. The denominator for the two above. */
    long countByTenantId(UUID tenantId);
}
