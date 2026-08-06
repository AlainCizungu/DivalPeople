package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectIdentifierRepository extends JpaRepository<SubjectIdentifier, UUID> {

    Optional<SubjectIdentifier> findByIdentifierTypeAndNormalizedValue(IdentifierType type, String normalizedValue);

    /** Identifier documents reused across different subjects are a primary fraud signal. */
    List<SubjectIdentifier> findAllByIdentifierTypeAndNormalizedValue(IdentifierType type, String normalizedValue);
}
