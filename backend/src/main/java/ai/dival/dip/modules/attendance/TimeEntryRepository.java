package ai.dival.dip.modules.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

    @EntityGraph(attributePaths = {"employee", "supersedes"})
    Optional<TimeEntry> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Live entries for a period. Superseded rows are history, not hours. */
    @EntityGraph(attributePaths = {"employee", "supersedes"})
    @Query("""
            select e from TimeEntry e
            where e.tenantId = :tenantId
              and e.employee.id = :employeeId
              and e.superseded = false
              and e.workDate between :from and :to
            order by e.startedAt asc
            """)
    List<TimeEntry> findLiveBetween(@Param("tenantId") UUID tenantId,
                                    @Param("employeeId") UUID employeeId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /** Everything on a day, superseded rows included, so an amendment trail can be read. */
    @EntityGraph(attributePaths = {"employee", "supersedes"})
    List<TimeEntry> findByTenantIdAndEmployeeIdAndWorkDateOrderByStartedAtAsc(
            UUID tenantId, UUID employeeId, LocalDate workDate);

    @Query("""
            select e from TimeEntry e
            where e.tenantId = :tenantId
              and e.employee.id = :employeeId
              and e.superseded = false
              and e.endedAt is null
            """)
    Optional<TimeEntry> findOpen(@Param("tenantId") UUID tenantId,
                                 @Param("employeeId") UUID employeeId);

    /**
     * Live entries whose span could touch a proposed one.
     *
     * <p>An open entry has no end, so it is treated as running forever for this check — which is
     * the honest reading: somebody still clocked in overlaps anything that starts after them.
     */
    @Query("""
            select e from TimeEntry e
            where e.tenantId = :tenantId
              and e.employee.id = :employeeId
              and e.superseded = false
              and e.startedAt < :end
              and (e.endedAt is null or e.endedAt > :start)
            """)
    List<TimeEntry> findOverlapping(@Param("tenantId") UUID tenantId,
                                    @Param("employeeId") UUID employeeId,
                                    @Param("start") Instant start,
                                    @Param("end") Instant end);
}
