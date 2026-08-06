package ai.dival.dip.modules.leave;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveLedgerEntryRepository extends JpaRepository<LeaveLedgerEntry, UUID> {

    List<LeaveLedgerEntry> findByTenantIdAndBalanceIdOrderByCreatedAtAsc(
            UUID tenantId, UUID balanceId);
}
