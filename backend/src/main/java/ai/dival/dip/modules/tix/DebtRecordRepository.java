package ai.dival.dip.modules.tix;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DebtRecordRepository extends JpaRepository<DebtRecord, UUID> {

    /**
     * Records belonging to the calling operator. The tenant is always passed explicitly — no
     * tenant-owned entity is ever fetched without a tenant predicate.
     */
    List<DebtRecord> findByTenantId(UUID tenantId);

    List<DebtRecord> findByTenantIdAndSubjectId(UUID tenantId, UUID subjectId);

    Optional<DebtRecord> findByIdAndTenantId(UUID id, UUID tenantId);

    /** This operator's records about one subject, whatever their status. */
    List<DebtRecord> findByTenantIdAndSubjectIdOrderByDefaultDateDesc(UUID tenantId, UUID subjectId);

    /** Records this operator suppressed for a given rights case, so closing it lifts exactly those. */
    List<DebtRecord> findByTenantIdAndSuppressedByRequestId(UUID tenantId, UUID requestId);

    /**
     * Exchange query: records held by <em>any</em> operator for a subject.
     *
     * <p>This deliberately crosses the tenant boundary and must only be called from
     * {@link ExchangeService}, which authorizes and audits every use.
     */
    @Query("select d from DebtRecord d where d.subject.id = :subjectId and d.status in :statuses "
            + "and d.retentionUntil >= :today")
    List<DebtRecord> findAcrossOperators(@Param("subjectId") UUID subjectId,
                                         @Param("statuses") List<DebtStatus> statuses,
                                         @Param("today") LocalDate today);

    /**
     * Records whose retention period has run out, for the calling tenant only.
     *
     * <p>Tenant-scoped on purpose, even though erasure is a system-wide obligation. Deleting
     * across the tenant boundary would need a row-level security policy permitting cross-tenant
     * writes, and that policy would then exist — available to anything else that later wanted it.
     * The purge iterates tenants and erases inside each one's own boundary instead, which is
     * slower and leaves the boundary intact.
     */
    List<DebtRecord> findByTenantIdAndRetentionUntilBefore(UUID tenantId, LocalDate today);

    // A cross-operator count of prior defaults lived here briefly and was removed before it ran.
    // Récidive does need to be judged across operators, but reading across them requires exchange
    // mode — and exchange mode appears in the policy's USING clause, which governs DELETE as well
    // as SELECT. Turning it on inside the write transaction that declares a debt would open a
    // window in which a cross-tenant delete was possible, to answer a question that has a purely
    // local answer: subjects are only ever created by declaration, so a subject that already
    // existed had already defaulted somewhere. SubjectResolver.Resolution.created() carries it.
}
