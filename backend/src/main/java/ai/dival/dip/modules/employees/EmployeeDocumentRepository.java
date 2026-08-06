package ai.dival.dip.modules.employees;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, UUID> {

    List<EmployeeDocument> findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
            UUID tenantId, UUID employeeId);

    Optional<EmployeeDocument> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<EmployeeDocument> findByTenantIdAndStoredFileId(UUID tenantId, UUID storedFileId);

    /**
     * Documents approaching expiry that have not already been flagged.
     *
     * <p>Mirrors the contract scan: alert once, so a daily job does not retrain people to ignore
     * the feed.
     */
    @Query("""
            select d from EmployeeDocument d
            where d.tenantId = :tenantId
              and d.expiresOn is not null
              and d.expiresOn <= :cutoff
              and d.expiryNotifiedAt is null
            """)
    List<EmployeeDocument> findExpiringWithoutAlert(
            @Param("tenantId") UUID tenantId, @Param("cutoff") LocalDate cutoff);
}
