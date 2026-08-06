package ai.dival.dip.modules.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class NotificationServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private NotificationService service;

    private UUID tenantA;
    private UUID tenantB;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("N A", "n-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        tenantB = tenants.save(new Tenant("N B", "n-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a notification stores its key and parameters, not rendered text")
    void storesKeyAndParams() {
        Notification raised = service.notify(alice, "notification.contractExpiring",
                Map.of("employee", "Jean Kabila", "days", "30"),
                Notification.Severity.WARNING, "Contract", "abc");

        assertThat(raised.getMessageKey()).isEqualTo("notification.contractExpiring");
        assertThat(raised.getParams())
                .containsEntry("employee", "Jean Kabila")
                .containsEntry("days", "30");
        assertThat(raised.getSeverity()).isEqualTo(Notification.Severity.WARNING);
        assertThat(raised.isRead()).isFalse();
    }

    @Test
    @DisplayName("a user sees only their own notifications")
    void addressedToOneRecipient() {
        service.notify(alice, "notification.a", Map.of(), Notification.Severity.INFO, null, null);
        service.notify(bob, "notification.b", Map.of(), Notification.Severity.INFO, null, null);

        assertThat(service.listFor(alice)).hasSize(1);
        assertThat(service.listFor(bob)).hasSize(1);
        assertThat(service.listFor(alice).get(0).getMessageKey()).isEqualTo("notification.a");
    }

    @Test
    @DisplayName("unread count tracks reading")
    void unreadCount() {
        Notification first = service.notify(
                alice, "notification.a", Map.of(), Notification.Severity.INFO, null, null);
        service.notify(alice, "notification.b", Map.of(), Notification.Severity.INFO, null, null);

        assertThat(service.unreadCountFor(alice)).isEqualTo(2);

        service.markRead(first.getId(), alice);
        assertThat(service.unreadCountFor(alice)).isEqualTo(1);

        assertThat(service.markAllRead(alice)).isEqualTo(1);
        assertThat(service.unreadCountFor(alice)).isZero();
    }

    @Test
    @DisplayName("marking read twice is not an error and does not move the timestamp")
    void markReadIsIdempotent() {
        Notification raised = service.notify(
                alice, "notification.a", Map.of(), Notification.Severity.INFO, null, null);

        var firstReadAt = service.markRead(raised.getId(), alice).getReadAt();
        var secondReadAt = service.markRead(raised.getId(), alice).getReadAt();

        assertThat(secondReadAt).isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("one user cannot mark another's notification read")
    void cannotReadSomeoneElses() {
        Notification forAlice = service.notify(
                alice, "notification.a", Map.of(), Notification.Severity.INFO, null, null);

        assertThatThrownBy(() -> service.markRead(forAlice.getId(), bob))
                .isInstanceOf(NotificationService.NotificationNotFoundException.class);
    }

    @Test
    @DisplayName("notifications do not cross tenants")
    void tenantScoped() {
        service.notify(alice, "notification.a", Map.of(), Notification.Severity.INFO, null, null);

        assertThat(TenantContext.runAsResult(tenantB, () -> service.listFor(alice))).isEmpty();
        assertThat(TenantContext.runAsResult(tenantB, () -> service.unreadCountFor(alice))).isZero();
    }

    @Test
    @DisplayName("notifying several people creates one record each")
    void notifiesMany() {
        List<Notification> raised = service.notifyAll(
                List.of(alice, bob, alice), "notification.team", Map.of(),
                Notification.Severity.INFO, null, null);

        // The duplicate recipient is collapsed: one person, one notification.
        assertThat(raised).hasSize(2);
        assertThat(service.listFor(alice)).hasSize(1);
    }

    @Test
    @DisplayName("a notification needs a recipient and a key")
    void validatesInput() {
        assertThatThrownBy(() -> service.notify(
                null, "notification.a", Map.of(), Notification.Severity.INFO, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.notify(
                alice, "  ", Map.of(), Notification.Severity.INFO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
