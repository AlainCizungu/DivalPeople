package ai.dival.dip.modules.recruitment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobOfferRepository extends JpaRepository<JobOffer, UUID> {

    @EntityGraph(attributePaths = {"application", "application.candidate", "orgUnit"})
    List<JobOffer> findByTenantIdAndApplicationIdOrderByCreatedAtDesc(
            UUID tenantId, UUID applicationId);

    @EntityGraph(attributePaths = {"application", "application.candidate", "orgUnit"})
    Optional<JobOffer> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Offers past their deadline with no answer.
     *
     * <p>Swept rather than computed on read, so an offer that has lapsed says so on every screen
     * instead of depending on which code path asked.
     */
    @Query("""
            select o from JobOffer o
            where o.tenantId = :tenantId
              and o.status = ai.dival.dip.modules.recruitment.OfferStatus.SENT
              and o.expiresOn is not null
              and o.expiresOn < :today
            """)
    List<JobOffer> findLapsed(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today);
}
