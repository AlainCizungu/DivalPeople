package ai.dival.dip.modules.files;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    Optional<StoredFile> findByIdAndTenantId(UUID id, UUID tenantId);

    List<StoredFile> findByTenantIdAndCategoryOrderByCreatedAtDesc(UUID tenantId, String category);

    /** Same bytes already uploaded by this tenant, so a re-upload need not be stored twice. */
    List<StoredFile> findByTenantIdAndChecksumSha256(UUID tenantId, String checksum);
}
