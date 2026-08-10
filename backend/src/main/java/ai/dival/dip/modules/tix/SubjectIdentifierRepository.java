package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reading identifiers, always with a scope.
 *
 * <p>There is deliberately no method here that looks an identifier up by type and value alone.
 * That method existed, every caller used it, and it was correct for as long as every identifier
 * was a national document. The moment one of them is an operator's own account number, the same
 * call resolves a rival's customer — so the choice between "unique across the exchange" and
 * "unique inside this operator" is made once, in {@link #locate}, by asking the type rather than
 * by trusting each caller to remember.
 */
public interface SubjectIdentifierRepository extends JpaRepository<SubjectIdentifier, UUID> {

    /**
     * The one lookup. Which of the two queries below runs is decided by the identifier's own
     * nature, not by the caller's intent, because a caller who could choose could choose wrong.
     *
     * @param askingTenantId the operator asking. Used only for an operator-scoped type, where it
     *                       is part of the identity; ignored for a national document, which is the
     *                       same document whoever presents it
     */
    default Optional<SubjectIdentifier> locate(
            IdentifierType type, String normalizedValue, UUID askingTenantId) {
        return type.isOperatorScoped()
                ? findByIdentifierTypeAndNormalizedValueAndOwnerTenantId(
                        type, normalizedValue, askingTenantId)
                : findByIdentifierTypeAndNormalizedValueAndOwnerTenantIdIsNull(type, normalizedValue);
    }

    /**
     * Identifier documents reused across different subjects are a primary fraud signal.
     *
     * <p>Scoped for the same reason as {@link #locate}, and the reason is sharper here: two
     * operators numbering their customers from one upwards produce colliding account references by
     * construction. Counting those as a reused document would report fraud on essentially every
     * imported row, which is worse than reporting none — a signal that fires always carries no
     * information and trains whoever reads it to ignore the ones that matter.
     */
    default List<SubjectIdentifier> reuses(
            IdentifierType type, String normalizedValue, UUID askingTenantId) {
        return type.isOperatorScoped()
                ? findAllByIdentifierTypeAndNormalizedValueAndOwnerTenantId(
                        type, normalizedValue, askingTenantId)
                : findAllByIdentifierTypeAndNormalizedValueAndOwnerTenantIdIsNull(
                        type, normalizedValue);
    }

    Optional<SubjectIdentifier> findByIdentifierTypeAndNormalizedValueAndOwnerTenantIdIsNull(
            IdentifierType type, String normalizedValue);

    Optional<SubjectIdentifier> findByIdentifierTypeAndNormalizedValueAndOwnerTenantId(
            IdentifierType type, String normalizedValue, UUID ownerTenantId);

    List<SubjectIdentifier> findAllByIdentifierTypeAndNormalizedValueAndOwnerTenantIdIsNull(
            IdentifierType type, String normalizedValue);

    List<SubjectIdentifier> findAllByIdentifierTypeAndNormalizedValueAndOwnerTenantId(
            IdentifierType type, String normalizedValue, UUID ownerTenantId);
}
