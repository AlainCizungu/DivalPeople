package ai.dival.dip.modules.tix;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RelationshipRepository extends JpaRepository<Relationship, UUID> {

    Optional<Relationship> findByTenantIdAndAccountReference(UUID tenantId, String accountReference);

    List<Relationship> findByTenantIdAndSubjectId(UUID tenantId, UUID subjectId);

    /**
     * Every operator's accounts against one subject, with their events, in one query.
     *
     * <p>Requires exchange mode. This is the read DIP Credit Intelligence is built on: the point is
     * that several institutions' histories can be counted together, and without the flag the RLS
     * policy narrows it to the caller's own accounts and the answer becomes a plausible lie.
     *
     * <p>{@code join fetch} on the events, deliberately. The alternative is one query for the
     * accounts and one per account for its events, which for a company with forty accounts is
     * forty-one round trips to produce a single percentage. That was the front door's original
     * mistake and it is not being repeated here.
     *
     * <p>Retention is applied to the account rather than to the event. An account whose retention
     * has expired takes its whole history with it — filtering events individually would leave an
     * account visible with holes in it, which reads as a company with gaps in its record rather
     * than as data that was correctly erased.
     */
    @Query("select distinct r from Relationship r "
            + "left join fetch r.events "
            + "where r.subject.id = :subjectId and r.retentionUntil >= :today")
    List<Relationship> findAcrossOperatorsWithEvents(@Param("subjectId") UUID subjectId,
                                                     @Param("today") LocalDate today);

    /**
     * One operator's accounts whose retention has run out.
     *
     * <p>Deleting one cascades to its events, which is how erasure of a payment history happens:
     * at the account, all at once, so no single inconvenient event can be removed on its own.
     *
     * <p><strong>Scoped to a tenant, and there is deliberately no cross-tenant version.</strong>
     * The policy on this table reads {@code USING (tenant_id = app_current_tenant() OR
     * app_exchange_mode())}, and a USING clause governs DELETE as well as SELECT — so a query that
     * found every expired account everywhere would, run inside exchange mode, hand somebody a
     * cross-tenant delete. {@link RetentionPurge} binds each operator in turn for exactly this
     * reason and this method exists in the shape that purge needs.
     */
    List<Relationship> findByTenantIdAndRetentionUntilBefore(UUID tenantId, LocalDate today);
}
