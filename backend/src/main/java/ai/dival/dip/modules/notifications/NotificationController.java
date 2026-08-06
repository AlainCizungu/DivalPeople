package ai.dival.dip.modules.notifications;

import ai.dival.dip.modules.users.CurrentUserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's own notifications.
 *
 * <p>There is no endpoint to read someone else's, and no endpoint to raise one. Notifications are
 * produced by the domain when something actually happens; an API that let a client invent them
 * would make the whole feed untrustworthy.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final CurrentUserService currentUser;

    public NotificationController(NotificationService notifications, CurrentUserService currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<NotificationResponse> list() {
        return notifications.listFor(me()).stream().map(NotificationResponse::from).toList();
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(notifications.unreadCountFor(me()));
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable UUID id) {
        return NotificationResponse.from(notifications.markRead(id, me()));
    }

    @PostMapping("/read-all")
    public MarkAllReadResponse markAllRead() {
        return new MarkAllReadResponse(notifications.markAllRead(me()));
    }

    private UUID me() {
        return currentUser.requireCurrentUser().getId();
    }

    public record UnreadCountResponse(long unread) {
    }

    public record MarkAllReadResponse(int marked) {
    }

    /**
     * The message key and parameters travel to the client, which renders them in the reader's
     * language. The server never picks the wording.
     */
    public record NotificationResponse(
            UUID id,
            String messageKey,
            Map<String, String> params,
            Notification.Severity severity,
            String resourceType,
            String resourceId,
            boolean read,
            Instant createdAt) {

        static NotificationResponse from(Notification notification) {
            return new NotificationResponse(
                    notification.getId(),
                    notification.getMessageKey(),
                    notification.getParams(),
                    notification.getSeverity(),
                    notification.getResourceType(),
                    notification.getResourceId(),
                    notification.isRead(),
                    notification.getCreatedAt());
        }
    }
}
