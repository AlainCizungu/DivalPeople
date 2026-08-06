package ai.dival.dip.modules.tix;

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

    /**
     * Exchange query: records held by <em>any</em> operator for a subject.
     *
     * <p>This deliberately crosses the tenant boundary and must only be called from
     * {@link ExchangeService}, which authorizes and audits every use.
     */
    @Query("select d from DebtRecord d where d.subject.id = :subjectId and d.status in :statuses")
    List<DebtRecord> findAcrossOperators(@Param("subjectId") UUID subjectId,
                                         @Param("statuses") List<DebtStatus> statuses);
}
