package ai.dival.dip.modules.lifecycle;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, UUID> {

    @EntityGraph(attributePaths = {"checklist", "checklist.employee", "assignee"})
    Optional<ChecklistItem> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Steps past their due date on a list that is still running, not yet alerted.
     *
     * <p>Items on a cancelled or completed list are excluded: chasing somebody about a hire that
     * fell through is how a notification feed earns the right to be ignored.
     */
    @Query("""
            select i from ChecklistItem i
            where i.tenantId = :tenantId
              and i.status = ai.dival.dip.modules.lifecycle.ItemStatus.PENDING
              and i.dueOn is not null
              and i.dueOn < :today
              and i.overdueNotifiedAt is null
              and i.checklist.status = ai.dival.dip.modules.lifecycle.ChecklistStatus.IN_PROGRESS
            order by i.dueOn asc
            """)
    List<ChecklistItem> findOverdueWithoutAlert(@Param("tenantId") UUID tenantId,
                                                @Param("today") LocalDate today);
}
