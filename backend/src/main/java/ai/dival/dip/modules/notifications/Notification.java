package ai.dival.dip.modules.notifications;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something a user should know about.
 *
 * <p>Holds a message key and parameters rather than a sentence. A notification raised while one
 * colleague works in French must read correctly to another working in English, and a row written
 * months ago must follow whatever language the reader picks today — which only works if the text
 * is produced at read time.
 */
@Entity
@Table(name = "notification")
public class Notification extends TenantOwnedEntity {

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "message_key", nullable = false, updatable = false, length = 200)
    private String messageKey;

    @Convert(converter = NotificationParamsConverter.class)
    @Column(name = "params", nullable = false, updatable = false)
    private Map<String, String> params = Map.of();

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private Severity severity = Severity.INFO;

    @Column(name = "resource_type", updatable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", updatable = false, length = 100)
    private String resourceId;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
        // for JPA
    }

    public Notification(UUID recipientId, String messageKey, Map<String, String> params,
                        Severity severity, String resourceType, String resourceId) {
        this.recipientId = recipientId;
        this.messageKey = messageKey;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.severity = severity == null ? Severity.INFO : severity;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /** Idempotent: marking an already-read notification again is not an error. */
    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
