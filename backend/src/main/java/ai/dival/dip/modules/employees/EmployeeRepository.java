package ai.dival.dip.modules.employees;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    @EntityGraph(attributePaths = {"orgUnit", "manager"})
    List<Employee> findByTenantIdOrderByLastNameAscFirstNameAsc(UUID tenantId);

    @EntityGraph(attributePaths = {"orgUnit", "manager", "workPattern"})
    Optional<Employee> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Employee> findByTenantIdAndEmployeeNumber(UUID tenantId, String employeeNumber);

    List<Employee> findByTenantIdAndOrgUnitId(UUID tenantId, UUID orgUnitId);

    List<Employee> findByTenantIdAndManagerId(UUID tenantId, UUID managerId);

    long countByTenantIdAndStatus(UUID tenantId, EmployeeStatus status);

    /**
     * Everyone reporting into a manager, at any depth.
     *
     * <p>Used to refuse a reporting line that would close a loop. Derived by walking the manager
     * chain rather than from a cached structure, so it cannot disagree with the data it protects.
     */
    @Query(value = """
            WITH RECURSIVE reports AS (
                SELECT id FROM employee WHERE manager_id = :managerId AND tenant_id = :tenantId
                UNION ALL
                SELECT e.id
                FROM employee e
                JOIN reports ON e.manager_id = reports.id
                WHERE e.tenant_id = :tenantId
            )
            SELECT id FROM reports
            """, nativeQuery = true)
    List<UUID> findAllReportIds(@Param("tenantId") UUID tenantId, @Param("managerId") UUID managerId);
}
