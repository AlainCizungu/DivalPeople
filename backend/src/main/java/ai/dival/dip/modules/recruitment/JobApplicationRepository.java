package ai.dival.dip.modules.recruitment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobApplicationRepository extends JpaRepository<JobApplication, UUID> {

    @EntityGraph(attributePaths = {"requisition", "candidate"})
    List<JobApplication> findByTenantIdAndRequisitionIdOrderByCreatedAtDesc(
            UUID tenantId, UUID requisitionId);

    List<JobApplication> findByTenantIdAndCandidateIdOrderByCreatedAtDesc(
            UUID tenantId, UUID candidateId);

    @EntityGraph(attributePaths = {"requisition", "candidate"})
    Optional<JobApplication> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<JobApplication> findByTenantIdAndRequisitionIdAndCandidateId(
            UUID tenantId, UUID requisitionId, UUID candidateId);

    long countByTenantIdAndRequisitionIdAndStatus(
            UUID tenantId, UUID requisitionId, ApplicationStatus status);
}
