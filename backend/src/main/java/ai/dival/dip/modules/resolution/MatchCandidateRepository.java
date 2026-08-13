package ai.dival.dip.modules.resolution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Candidate pairs, which belong to the registry rather than to any operator.
 *
 * <p>No tenant predicate anywhere in this interface, and that is not the omission it looks like.
 * Every other repository in the platform is tenant-scoped because it holds one operator's data;
 * this holds the registry's own view of who is who, spanning operators by construction. It is
 * reachable only through {@link EntityResolutionService}, whose controller requires
 * {@code PLATFORM_ADMIN}.
 */
public interface MatchCandidateRepository extends JpaRepository<MatchCandidate, UUID> {

    /** The queue: least certain last, so the clearest cases are cleared first. */
    List<MatchCandidate> findByStatusOrderByConfidenceDesc(MatchStatus status, Limit limit);

    List<MatchCandidate> findByStatusOrderByDecidedAtDesc(MatchStatus status, Limit limit);

    /**
     * Whether this pair already has a case open.
     *
     * <p>The scan calls it once per candidate rather than relying on the unique index to refuse a
     * duplicate. Letting the constraint do the work would mean an exception per already-known pair
     * on every run, and a scan that logs a hundred violations to do nothing is a scan nobody
     * leaves switched on.
     */
    Optional<MatchCandidate> findBySubjectLowIdAndSubjectHighIdAndStatus(
            UUID subjectLowId, UUID subjectHighId, MatchStatus status);

    long countByStatus(MatchStatus status);
}
