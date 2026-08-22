package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The named groups, which are tenant-owned and never read across operators.
 *
 * <p>Named {@code WatchlistGroupRepository} rather than {@code WatchlistRepository}, which was
 * already taken by the entries. The older name is the wrong way round now that groups exist, and
 * renaming it would touch every caller of a working class for tidiness — so the newer, clearer
 * name goes on the newer thing and this comment explains the asymmetry to whoever notices it.
 */
public interface WatchlistGroupRepository extends JpaRepository<Watchlist, UUID> {

    List<Watchlist> findByTenantIdOrderByName(UUID tenantId);

    Optional<Watchlist> findByIdAndTenantId(UUID id, UUID tenantId);
}
