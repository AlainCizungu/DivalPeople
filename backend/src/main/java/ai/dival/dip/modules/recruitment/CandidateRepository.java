package ai.dival.dip.modules.recruitment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    List<Candidate> findByTenantIdOrderByLastNameAscFirstNameAsc(UUID tenantId);

    Optional<Candidate> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Email identifies the person, so this is how a repeat applicant is recognised. */
    Optional<Candidate> findByTenantIdAndEmail(UUID tenantId, String email);
}
