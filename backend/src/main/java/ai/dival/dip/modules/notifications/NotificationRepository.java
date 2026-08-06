package ai.dival.dip.modules.notifications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByTenantIdAndRecipientIdOrderByCreatedAtDesc(
            UUID tenantId, UUID recipientId, Pageable pageable);

    List<Notification> findByTenantIdAndRecipientIdAndReadAtIsNull(UUID tenantId, UUID recipientId);

    long countByTenantIdAndRecipientIdAndReadAtIsNull(UUID tenantId, UUID recipientId);

    /**
     * Scoped by recipient as well as tenant: within one tenant, a user still must not be able to
     * read or dismiss somebody else's notification by guessing an identifier.
     */
    Optional<Notification> findByIdAndTenantIdAndRecipientId(
            UUID id, UUID tenantId, UUID recipientId);
}
