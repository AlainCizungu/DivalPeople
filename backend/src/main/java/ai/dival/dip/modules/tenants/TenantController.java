package ai.dival.dip.modules.tenants;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform administration of tenants.
 *
 * <p>Every endpoint is restricted to {@code PLATFORM_ADMIN}. This is the one part of the API that
 * is not tenant-scoped, which is precisely why the authorization is coarse and absolute rather
 * than delegated to the service layer.
 *
 * <p>A platform administrator belongs to no tenant, so no local user record exists for them and
 * the audit actor may be null. That is expected: the audit entry still records the action, the
 * resource and the time.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
@PreAuthorize("hasRole('" + Roles.PLATFORM_ADMIN + "')")
public class TenantController {

    private final TenantService tenants;
    private final CurrentUserService currentUser;

    public TenantController(TenantService tenants, CurrentUserService currentUser) {
        this.tenants = tenants;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<TenantResponse> list() {
        return tenants.list().stream().map(TenantResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return TenantResponse.from(tenants.get(id));
    }

    @PostMapping
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant created = tenants.create(
                request.name(), request.slug(), request.edition(), request.defaultLocale(),
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse.from(created));
    }

    @PostMapping("/{id}/deactivate")
    public TenantResponse deactivate(@PathVariable UUID id) {
        return TenantResponse.from(tenants.deactivate(id, actorId()));
    }

    @PostMapping("/{id}/activate")
    public TenantResponse activate(@PathVariable UUID id) {
        return TenantResponse.from(tenants.activate(id, actorId()));
    }

    /** Null for a platform administrator, who has no tenant and therefore no local user record. */
    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    public record CreateTenantRequest(
            @NotBlank String name,
            @NotBlank String slug,
            @NotNull Tenant.Edition edition,
            @NotBlank String defaultLocale) {
    }

    public record TenantResponse(
            UUID id,
            String name,
            String slug,
            Tenant.Edition edition,
            String defaultLocale,
            boolean active,
            Instant createdAt) {

        static TenantResponse from(Tenant tenant) {
            return new TenantResponse(
                    tenant.getId(),
                    tenant.getName(),
                    tenant.getSlug(),
                    tenant.getEdition(),
                    tenant.getDefaultLocale(),
                    tenant.isActive(),
                    tenant.getCreatedAt());
        }
    }
}
