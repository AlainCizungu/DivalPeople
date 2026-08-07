package ai.dival.dip.modules.organizations;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * The organisation structure of the calling tenant.
 *
 * <p>Reading is open to any authenticated member, because almost every screen needs to show which
 * department someone belongs to. Changing the structure is restricted: it moves people, budgets
 * and approval chains at once.
 */
@RestController
@RequestMapping("/api/v1/organization/units")
@PreAuthorize("isAuthenticated()")
public class OrgUnitController {



    private final OrgUnitService units;
    private final CurrentUserService currentUser;

    public OrgUnitController(OrgUnitService units, CurrentUserService currentUser) {
        this.units = units;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<OrgUnitResponse> list() {
        return units.list().stream().map(OrgUnitResponse::from).toList();
    }

    @GetMapping("/{id}")
    public OrgUnitResponse get(@PathVariable UUID id) {
        return OrgUnitResponse.from(units.get(id));
    }

    @GetMapping("/{id}/children")
    public List<OrgUnitResponse> children(@PathVariable UUID id) {
        return units.children(id).stream().map(OrgUnitResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + Roles.TENANT_ADMIN + "', '" + Roles.HR_ADMIN + "')")
    public ResponseEntity<OrgUnitResponse> create(@Valid @RequestBody CreateOrgUnitRequest request) {
        OrgUnit created = units.create(
                request.parentId(), request.unitType(), request.code(), request.name(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrgUnitResponse.from(created));
    }

    @PostMapping("/{id}/rename")
    @PreAuthorize("hasAnyRole('" + Roles.TENANT_ADMIN + "', '" + Roles.HR_ADMIN + "')")
    public OrgUnitResponse rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return OrgUnitResponse.from(units.rename(id, request.name(), actorId()));
    }

    /** A null {@code parentId} moves the unit to the root, which only a legal entity may occupy. */
    @PostMapping("/{id}/move")
    @PreAuthorize("hasAnyRole('" + Roles.TENANT_ADMIN + "', '" + Roles.HR_ADMIN + "')")
    public OrgUnitResponse move(@PathVariable UUID id, @RequestBody MoveRequest request) {
        return OrgUnitResponse.from(units.move(id, request.parentId(), actorId()));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('" + Roles.TENANT_ADMIN + "', '" + Roles.HR_ADMIN + "')")
    public List<OrgUnitResponse> deactivate(@PathVariable UUID id) {
        return units.deactivate(id, actorId()).stream().map(OrgUnitResponse::from).toList();
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('" + Roles.TENANT_ADMIN + "', '" + Roles.HR_ADMIN + "')")
    public OrgUnitResponse activate(@PathVariable UUID id) {
        return OrgUnitResponse.from(units.activate(id, actorId()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    public record CreateOrgUnitRequest(
            UUID parentId,
            @NotNull OrgUnitType unitType,
            @NotBlank String code,
            @NotBlank String name) {
    }

    public record RenameRequest(@NotBlank String name) {
    }

    public record MoveRequest(UUID parentId) {
    }

    public record OrgUnitResponse(
            UUID id,
            UUID parentId,
            OrgUnitType unitType,
            String code,
            String name,
            int depth,
            boolean active) {

        static OrgUnitResponse from(OrgUnit unit) {
            return new OrgUnitResponse(
                    unit.getId(),
                    unit.getParent() == null ? null : unit.getParent().getId(),
                    unit.getUnitType(),
                    unit.getCode(),
                    unit.getName(),
                    unit.getDepth(),
                    unit.isActive());
        }
    }
}
