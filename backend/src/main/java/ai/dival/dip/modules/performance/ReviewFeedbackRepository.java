package ai.dival.dip.modules.performance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewFeedbackRepository extends JpaRepository<ReviewFeedback, UUID> {

    @EntityGraph(attributePaths = {"author", "review"})
    List<ReviewFeedback> findByTenantIdAndReviewIdOrderBySubmittedAtAsc(
            UUID tenantId, UUID reviewId);

    Optional<ReviewFeedback> findByTenantIdAndReviewIdAndAuthorId(
            UUID tenantId, UUID reviewId, UUID authorId);
}
