package ai.dival.dip.modules.organizations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrgUnitRepository extends JpaRepository<OrgUnit, UUID> {

    List<OrgUnit> findByTenantIdOrderByDepthAscNameAsc(UUID tenantId);

    Optional<OrgUnit> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<OrgUnit> findByTenantIdAndCode(UUID tenantId, String code);

    List<OrgUnit> findByTenantIdAndParentIsNull(UUID tenantId);

    List<OrgUnit> findByTenantIdAndParentId(UUID tenantId, UUID parentId);

    /**
     * Every descendant of a unit, at any depth.
     *
     * <p>A recursive walk of the parent chain rather than a stored path: containment is derived
     * from the relationship that actually defines it, so no denormalised column can drift and
     * quietly give the wrong answer to "is this inside that".
     */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM org_unit WHERE parent_id = :unitId AND tenant_id = :tenantId
                UNION ALL
                SELECT child.id
                FROM org_unit child
                JOIN descendants ON child.parent_id = descendants.id
                WHERE child.tenant_id = :tenantId
            )
            SELECT id FROM descendants
            """, nativeQuery = true)
    List<UUID> findDescendantIds(@Param("tenantId") UUID tenantId, @Param("unitId") UUID unitId);
}
