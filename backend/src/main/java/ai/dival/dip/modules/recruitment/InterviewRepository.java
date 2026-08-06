package ai.dival.dip.modules.recruitment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewRepository extends JpaRepository<Interview, UUID> {

    List<Interview> findByTenantIdAndApplicationIdOrderByScheduledAtAsc(
            UUID tenantId, UUID applicationId);

    Optional<Interview> findByIdAndTenantId(UUID id, UUID tenantId);

    /** An interviewer's upcoming schedule. */
    List<Interview> findByTenantIdAndInterviewerIdAndScheduledAtAfterOrderByScheduledAtAsc(
            UUID tenantId, UUID interviewerId, Instant from);
}
