package ai.dival.dip.modules.learning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, UUID> {

    List<Course> findByTenantIdOrderByTitleAsc(UUID tenantId);

    List<Course> findByTenantIdAndActiveTrueOrderByTitleAsc(UUID tenantId);

    List<Course> findByTenantIdAndMandatoryTrueAndActiveTrueOrderByTitleAsc(UUID tenantId);

    Optional<Course> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Course> findByTenantIdAndCode(UUID tenantId, String code);
}
