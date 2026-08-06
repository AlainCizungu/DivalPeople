package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    @Query("select distinct i.subject from SubjectIdentifier i "
            + "where i.identifierType = :type and i.normalizedValue = :value")
    Optional<Subject> findByIdentifier(@Param("type") IdentifierType type, @Param("value") String value);

    List<Subject> findByNormalizedName(String normalizedName);
}
