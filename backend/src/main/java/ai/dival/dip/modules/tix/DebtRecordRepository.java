package ai.dival.dip.modules.tix;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * @implNote The monthly count below is grouped in SQL rather than by loading rows and bucketing
 *           them in Java. One real import is 3,699 records for a single operator; a screen that
 *           read them all to produce twelve numbers would be the front door's original mistake
 *           repeated on a different page.
 */
public interface DebtRecordRepository extends JpaRepository<DebtRecord, UUID> {

    /**
     * Records belonging to the calling operator. The tenant is always passed explicitly — no
     * tenant-owned entity is ever fetched without a tenant predicate.
     */
    List<DebtRecord> findByTenantId(UUID tenantId);

    List<DebtRecord> findByTenantIdAndSubjectId(UUID tenantId, UUID subjectId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, DebtStatus status);

    /**
     * Records whose retention period ends inside a window.
     *
     * <p>Counted here rather than by pulling every record into the browser and comparing dates in
     * JavaScript, which is what the overview screen did. At a dozen records that was invisible; at
     * four thousand it is the page's whole payload.
     */
    long countByTenantIdAndRetentionUntilBetween(
            UUID tenantId, java.time.LocalDate from, java.time.LocalDate to);

    Optional<DebtRecord> findByIdAndTenantId(UUID id, UUID tenantId);

    /** This operator's records about one subject, whatever their status. */
    List<DebtRecord> findByTenantIdAndSubjectIdOrderByDefaultDateDesc(UUID tenantId, UUID subjectId);

    /** Records this operator suppressed for a given rights case, so closing it lifts exactly those. */
    List<DebtRecord> findByTenantIdAndSuppressedByRequestId(UUID tenantId, UUID requestId);

    /**
     * The records this operator derived from a given set of delivered rows.
     *
     * <p>Takes row ids rather than a batch id, and the awkwardness is deliberate. A debt record
     * remembers the row it came from and nothing about the batch that row belonged to; joining
     * from here to {@code import_batch} would mean this module reaching into {@code ingest}'s
     * tables, which is the boundary the whole design rests on. The caller in {@code tix} asks
     * ingest for the rows and passes their ids, which keeps the join in Java where it can be seen.
     *
     * <p>Callers chunk the ids. A delivery of four thousand rows in one IN clause is a statement
     * that works and should not be relied on to keep working.
     */
    List<DebtRecord> findByTenantIdAndRawRecordIdIn(UUID tenantId, java.util.Collection<UUID> rawRecordIds);

    /**
     * Exchange query: records held by <em>any</em> operator for a subject.
     *
     * <p>This deliberately crosses the tenant boundary and must only be called from
     * {@link ExchangeService}, which authorizes and audits every use.
     */
    @Query("select d from DebtRecord d where d.subject.id = :subjectId and d.status in :statuses "
            + "and d.retentionUntil >= :today")
    List<DebtRecord> findAcrossOperators(@Param("subjectId") UUID subjectId,
                                         @Param("statuses") List<DebtStatus> statuses,
                                         @Param("today") LocalDate today);

    /**
     * Subjects this operator holds a record against, matched by name or by any identifier.
     *
     * <p><strong>Tenant-scoped, and that is the entire security model of the search screen.</strong>
     * {@code tix_subject} carries no {@code tenant_id} — a subject is shared, because several
     * operators declare against the same person. So a query starting from the subject table would
     * search the whole national registry and let any participant enumerate every business every
     * competitor has reported. Starting from {@code tix_debt_record} and filtering on the tenant
     * means an operator can only find people it already knows about, which is the difference
     * between a search box and a bulk export.
     *
     * <p>Expired records are excluded. A record past its retention date must not be findable by
     * the operator that declared it any more than by anybody else — the difference between erasure
     * and concealment is that erasure applies to you too.
     */
    @Query("select distinct d.subject from DebtRecord d left join d.subject.identifiers i "
            + "where d.tenantId = :tenantId and d.retentionUntil >= :today "
            + "and (d.subject.normalizedName like concat('%', :name, '%') "
            + "     or i.normalizedValue = :identifier)")
    List<Subject> searchOwn(@Param("tenantId") UUID tenantId,
                            @Param("name") String normalizedName,
                            @Param("identifier") String normalizedIdentifier,
                            @Param("today") LocalDate today);

    /**
     * The subjects of one kind this operator holds a live record against, in name order.
     *
     * <p>The browse counterpart to {@link #searchOwn}, and tenant-scoped for the same reason and
     * more strongly: search at least requires somebody to type a name they already knew, and a
     * list does not. Starting from {@code tix_debt_record} means an operator can only browse
     * people it has itself reported, which is the difference between a directory of your own book
     * and a directory of the country.
     *
     * <p>Expired records are excluded, so a subject whose last record has run out of retention
     * disappears from the list as it disappears from everything else. Merged subjects are excluded
     * too — they hold nothing after a merge, so this is belt and braces rather than a filter that
     * does work.
     */
    @Query("select distinct d.subject from DebtRecord d "
            + "where d.tenantId = :tenantId and d.retentionUntil >= :today "
            + "and d.subject.subjectType = :type and d.subject.mergedInto is null "
            + "order by d.subject.normalizedName")
    List<Subject> listOwnByType(@Param("tenantId") UUID tenantId,
                                @Param("type") Subject.SubjectType type,
                                @Param("today") LocalDate today,
                                org.springframework.data.domain.Limit limit);

    /**
     * Records whose retention period has run out, for the calling tenant only.
     *
     * <p>Tenant-scoped on purpose, even though erasure is a system-wide obligation. Deleting
     * across the tenant boundary would need a row-level security policy permitting cross-tenant
     * writes, and that policy would then exist — available to anything else that later wanted it.
     * The purge iterates tenants and erases inside each one's own boundary instead, which is
     * slower and leaves the boundary intact.
     */
    List<DebtRecord> findByTenantIdAndRetentionUntilBefore(UUID tenantId, LocalDate today);

    // A cross-operator count of prior defaults lived here briefly and was removed before it ran.
    // Récidive does need to be judged across operators, but reading across them requires exchange
     /**
     * One row per company this operator is still owed money by, largest first.
     *
     * <p>Grouped in SQL. The alternative — read every record and total them in Java — is the
     * mistake the front door made with 3,699 rows, and the analyst would make it against the whole
     * book on every question.
     *
     * <p>Outstanding only, unexpired only, and one currency at a time. Summing across currencies
     * would need a rate this application has no business inventing, which is the same refusal the
     * risk model makes about exposure.
     *
     * <p>Columns: subject id, name, total owed, oldest default date, how many records.
     */
    @Query(value = "select s.id, s.full_name, sum(d.amount) as total, "
            + "min(d.default_date) as oldest, count(*) as records "
            + "from tix_debt_record d join tix_subject s on s.id = d.subject_id "
            + "where d.tenant_id = :tenantId and d.status = 'OUTSTANDING' "
            + "and d.currency = :currency and d.retention_until >= :today "
            + "group by s.id, s.full_name having sum(d.amount) >= :minAmount "
            + "order by total desc", nativeQuery = true)
    List<Object[]> exposureBySubject(@Param("tenantId") UUID tenantId,
                                     @Param("currency") String currency,
                                     @Param("today") LocalDate today,
                                     @Param("minAmount") BigDecimal minAmount);

    /** Records this operator added since a moment. What entered the book. */
    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant since);

    /**
     * How many records this operator declared in each month since a moment, oldest first.
     *
     * <p>Counted on {@code createdAt} — when the record entered the registry — and not on the
     * default date, which is when the obligation fell due. The two differ by however long the
     * operator took to send the file, sometimes by years, and a chart of activity that used the
     * second would be a chart of somebody's ageing rather than of what they did.
     */
    @Query(value = "select to_char(date_trunc('month', created_at at time zone 'UTC'), "
            + "'YYYY-MM') as month, count(*) as total "
            + "from tix_debt_record where tenant_id = :tenantId and created_at >= :since "
            + "group by 1 order by 1", nativeQuery = true)
    List<Object[]> countByMonth(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    // mode — and exchange mode appears in the policy's USING clause, which governs DELETE as well
    // as SELECT. Turning it on inside the write transaction that declares a debt would open a
    // window in which a cross-tenant delete was possible, to answer a question that has a purely
    // local answer: subjects are only ever created by declaration, so a subject that already
    // existed had already defaulted somewhere. SubjectResolver.Resolution.created() carries it.
}
