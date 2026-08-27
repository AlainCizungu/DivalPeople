package ai.dival.dip.modules.tix;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Appending to an account's history.
 *
 * <p>Deliberately thin. {@code JpaRepository} offers {@code delete} and {@code saveAll} on any
 * entity, but {@code dip_app} holds only SELECT and INSERT on this table — so a caller that reaches
 * for a deletion gets a database error rather than a silent erasure, and the privilege is the
 * enforcement rather than this interface's shape.
 *
 * <p>No finders. Reading an account's events happens through {@link Relationship}, which orders
 * them by when things happened; a second way to read the same rows would eventually order them
 * differently.
 */
public interface RelationshipEventRepository extends JpaRepository<RelationshipEvent, UUID> {
}
