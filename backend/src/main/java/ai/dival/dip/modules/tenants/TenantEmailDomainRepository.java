package ai.dival.dip.modules.tenants;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Domains, read by whoever is joining and written by the platform operator.
 *
 * <p>Both queries below are governed by row-level security, and which of them can see anything
 * depends entirely on the transaction they run in. {@link #findByDomain} is asked by somebody who
 * has just registered and belongs to no institution yet, so it only returns a row inside a
 * transaction that has set exchange mode — see {@code JoiningService}. {@link #findByTenantId} is
 * asked with a tenant bound and returns that institution's own rows.
 *
 * <p>There is no update. A mapping is added or removed, never edited: an edited row would move
 * every future joiner into a different institution with nothing in the record saying when or by
 * whom, and the accounts that already joined under the old value would be sitting in the wrong
 * book.
 */
public interface TenantEmailDomainRepository extends JpaRepository<TenantEmailDomain, UUID> {

    /**
     * The institution a domain belongs to, if any.
     *
     * <p>{@code Optional} rather than a list because the unique index makes more than one
     * impossible. If this ever returns a duplicate-key surprise, the index was dropped and the
     * question "whose records may this person read" has stopped having one answer.
     */
    Optional<TenantEmailDomain> findByDomain(String domain);

    List<TenantEmailDomain> findByTenantIdOrderByDomain(UUID tenantId);
}
