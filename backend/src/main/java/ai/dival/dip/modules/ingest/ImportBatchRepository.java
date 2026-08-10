package ai.dival.dip.modules.ingest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    /**
     * The operator's deliveries, with the source they came from already loaded.
     *
     * <p>The join fetch is the whole point of writing this by hand. Every response carries the
     * source's code, {@code dataSource} is a lazy association, and the response is built in the
     * controller after the transaction has closed — so without this the read throws
     * LazyInitializationException and the screen shows "Internal Server Error".
     *
     * <p>It did not fail immediately, which is what made it worth a query rather than a comment.
     * A batch whose source happened to be in the persistence context already — freshly registered
     * in the same request, or fetched by something earlier — reads fine. It broke the first time
     * somebody loaded the screen against a batch uploaded in an earlier session.
     */
    @Query("select b from ImportBatch b join fetch b.dataSource "
            + "where b.tenantId = :tenantId order by b.receivedAt desc")
    List<ImportBatch> findByTenantIdOrderByReceivedAtDesc(@Param("tenantId") UUID tenantId);

    /** Fetched the same way and for the same reason: the detail screen reads the source code. */
    @Query("select b from ImportBatch b join fetch b.dataSource "
            + "where b.id = :id and b.tenantId = :tenantId")
    Optional<ImportBatch> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /**
     * Whether this exact file is already live for this operator.
     *
     * <p>Checked before publishing so a duplicate upload gets a sentence it can act on rather
     * than a constraint violation at flush time. The partial unique index in V20 is the real
     * guarantee and stays — this is the readable half of the same rule.
     */
    Optional<ImportBatch> findByTenantIdAndChecksumSha256AndStatus(
            UUID tenantId, String checksumSha256, BatchStatus status);
}
