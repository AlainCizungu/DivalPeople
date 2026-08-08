package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRequestRepository extends JpaRepository<SubjectRequest, UUID> {

    List<SubjectRequest> findByTenantIdOrderByRaisedAtDesc(UUID tenantId);

    Optional<SubjectRequest> findByIdAndTenantId(UUID id, UUID tenantId);
}
