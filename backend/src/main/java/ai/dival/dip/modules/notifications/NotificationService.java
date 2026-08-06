package ai.dival.dip.modules.notifications;

import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivering notifications to users, and letting them clear them.
 *
 * <p>This is the in-app channel. Email and SMS are separate delivery adapters that will consume
 * the same records; keeping the record independent of how it was delivered means a notification
 * is not lost when a mail provider is down, and a user does not see it twice when it recovers.
 */
@Service
public class NotificationService {

    /** A page of history. Anything older is a reporting question, not a notification one. */
    private static final int DEFAULT_LIMIT = 50;

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * Raises a notification for a user in the current tenant.
     *
     * @param messageKey translation key the client resolves, never rendered text
     * @param params     substitution values for that key
     */
    @Transactional
    public Notification notify(UUID recipientId, String messageKey, Map<String, String> params,
                               Notification.Severity severity, String resourceType,
                               String resourceId) {
        if (recipientId == null) {
            throw new IllegalArgumentException("A notification needs a recipient");
        }
        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException("A notification needs a message key");
        }
        return notifications.save(new Notification(
                recipientId, messageKey, params, severity, resourceType, resourceId));
    }

    /** Raises the same notification for several people — an approval queue, a team alert. */
    @Transactional
    public List<Notification> notifyAll(List<UUID> recipientIds, String messageKey,
                                        Map<String, String> params,
                                        Notification.Severity severity, String resourceType,
                                        String resourceId) {
        return recipientIds.stream()
                .distinct()
                .map(recipient -> notify(
                        recipient, messageKey, params, severity, resourceType, resourceId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Notification> listFor(UUID recipientId) {
        return notifications.findByTenantIdAndRecipientIdOrderByCreatedAtDesc(
                TenantContext.require(), recipientId, PageRequest.of(0, DEFAULT_LIMIT));
    }

    @Transactional(readOnly = true)
    public long unreadCountFor(UUID recipientId) {
        return notifications.countByTenantIdAndRecipientIdAndReadAtIsNull(
                TenantContext.require(), recipientId);
    }

    @Transactional
    public Notification markRead(UUID id, UUID recipientId) {
        Notification notification = notifications
                .findByIdAndTenantIdAndRecipientId(id, TenantContext.require(), recipientId)
                .orElseThrow(() -> new NotificationNotFoundException(id));
        notification.markRead();
        return notification;
    }

    /** @return how many were actually unread, so the caller can report something truthful */
    @Transactional
    public int markAllRead(UUID recipientId) {
        List<Notification> unread = notifications
                .findByTenantIdAndRecipientIdAndReadAtIsNull(TenantContext.require(), recipientId);
        unread.forEach(Notification::markRead);
        return unread.size();
    }

    /** Deliberately does not reveal whether the notification exists for someone else. */
    public static class NotificationNotFoundException extends ResourceNotFoundException {
        public NotificationNotFoundException(UUID id) {
            super("Notification not found: " + id);
        }
    }
}
