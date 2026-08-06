package ai.dival.dip.modules.organizations;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organisation tree for the calling tenant.
 *
 * <p>Every read and write is scoped to {@link TenantContext}; nothing here takes a tenant from
 * the caller. Structural rules — a root must be a legal entity, a unit cannot be moved beneath
 * its own descendant — are enforced here rather than in the entity, because they need a view of
 * the whole tree.
 */
@Service
public class OrgUnitService {

    private static final int MAX_DEPTH = 12;

    private final OrgUnitRepository units;
    private final AuditService audit;

    public OrgUnitService(OrgUnitRepository units, AuditService audit) {
        this.units = units;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<OrgUnit> list() {
        return units.findByTenantIdOrderByDepthAscNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public OrgUnit get(UUID id) {
        return units.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new OrgUnitNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<OrgUnit> children(UUID parentId) {
        return units.findByTenantIdAndParentId(TenantContext.require(), parentId);
    }

    /**
     * Creates a unit.
     *
     * @param parentId the containing unit, or {@code null} to create a root legal entity
     */
    @Transactional
    public OrgUnit create(UUID parentId, OrgUnitType type, String code, String name, UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Organization unit name is required");
        }

        String normalizedCode = OrgUnit.normalizeCode(code);
        if (normalizedCode.isBlank()) {
            throw new IllegalArgumentException("Organization unit code is required");
        }
        if (units.findByTenantIdAndCode(tenantId, normalizedCode).isPresent()) {
            throw new CodeAlreadyUsedException(normalizedCode);
        }

        OrgUnit parent = null;
        if (parentId == null) {
            if (!type.canBeRoot()) {
                throw new IllegalArgumentException(
                        "A root organization unit must be a LEGAL_ENTITY");
            }
        } else {
            parent = get(parentId);
            if (parent.getDepth() + 1 > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Organization hierarchy may not exceed " + MAX_DEPTH + " levels");
            }
        }

        OrgUnit created = units.save(new OrgUnit(type, normalizedCode, name, parent));
        audit.recordSuccess("ORG_UNIT_CREATED", "OrgUnit", created.getId().toString(), actorId);
        return created;
    }

    @Transactional
    public OrgUnit rename(UUID id, String name, UUID actorId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Organization unit name is required");
        }
        OrgUnit unit = get(id);
        unit.rename(name);
        audit.recordSuccess("ORG_UNIT_RENAMED", "OrgUnit", id.toString(), actorId);
        return unit;
    }

    /**
     * Moves a unit under a different parent, or to the root when {@code newParentId} is null.
     *
     * <p>Refuses to create a cycle. Moving a unit beneath its own descendant would detach that
     * whole branch from the tree while leaving every row individually valid — an orphaned island
     * that no query would report as broken.
     */
    @Transactional
    public OrgUnit move(UUID id, UUID newParentId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        OrgUnit unit = get(id);

        if (newParentId == null) {
            if (!unit.getUnitType().canBeRoot()) {
                throw new IllegalArgumentException(
                        "Only a LEGAL_ENTITY may sit at the root of the organization");
            }
            unit.reattachTo(null);
        } else {
            if (newParentId.equals(id)) {
                throw new IllegalArgumentException("An organization unit cannot contain itself");
            }
            Set<UUID> descendants = Set.copyOf(units.findDescendantIds(tenantId, id));
            if (descendants.contains(newParentId)) {
                throw new IllegalArgumentException(
                        "An organization unit cannot be moved beneath one of its own descendants");
            }

            OrgUnit newParent = get(newParentId);
            if (newParent.getDepth() + 1 > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Organization hierarchy may not exceed " + MAX_DEPTH + " levels");
            }
            unit.reattachTo(newParent);
        }

        refreshDepths(tenantId, unit);
        audit.recordSuccess("ORG_UNIT_MOVED", "OrgUnit", id.toString(), actorId);
        return unit;
    }

    /**
     * Deactivates a unit and everything beneath it.
     *
     * <p>Retiring a department while its teams stay active would leave people reporting into
     * something that no longer exists, so the whole branch goes together. Rows are kept, because
     * historical records reference them.
     */
    @Transactional
    public List<OrgUnit> deactivate(UUID id, UUID actorId) {
        UUID tenantId = TenantContext.require();
        OrgUnit unit = get(id);

        List<UUID> affected = new ArrayList<>(units.findDescendantIds(tenantId, id));
        affected.add(id);

        List<OrgUnit> deactivated = units.findAllById(affected).stream()
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .peek(OrgUnit::deactivate)
                .toList();

        audit.recordSuccess("ORG_UNIT_DEACTIVATED", "OrgUnit", unit.getId().toString(), actorId);
        return deactivated;
    }

    @Transactional
    public OrgUnit activate(UUID id, UUID actorId) {
        OrgUnit unit = get(id);
        if (unit.getParent() != null && !unit.getParent().isActive()) {
            throw new ConflictException(
                    "Reactivate the parent organization unit before this one");
        }
        unit.activate();
        audit.recordSuccess("ORG_UNIT_ACTIVATED", "OrgUnit", id.toString(), actorId);
        return unit;
    }

    /** Rewrites depth for a moved unit and its whole subtree. */
    private void refreshDepths(UUID tenantId, OrgUnit moved) {
        Map<UUID, List<OrgUnit>> byParent = units
                .findByTenantIdOrderByDepthAscNameAsc(tenantId).stream()
                .filter(unit -> unit.getParent() != null)
                .collect(Collectors.groupingBy(unit -> unit.getParent().getId()));

        Deque<OrgUnit> pending = new ArrayDeque<>();
        pending.add(moved);
        while (!pending.isEmpty()) {
            OrgUnit current = pending.poll();
            for (OrgUnit child : byParent.getOrDefault(current.getId(), List.of())) {
                child.setDepth(current.getDepth() + 1);
                pending.add(child);
            }
        }
    }

    /** Groups units by parent so a caller can render a tree without N+1 queries. */
    @Transactional(readOnly = true)
    public Map<UUID, List<OrgUnit>> childrenByParent() {
        return list().stream()
                .filter(unit -> unit.getParent() != null)
                .collect(Collectors.groupingBy(unit -> unit.getParent().getId(),
                        Collectors.toList()));
    }

    /** Convenience for callers that need units keyed by id. */
    @Transactional(readOnly = true)
    public Map<UUID, OrgUnit> byId() {
        return list().stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    }

    public static class OrgUnitNotFoundException extends ResourceNotFoundException {
        public OrgUnitNotFoundException(UUID id) {
            super("Organization unit not found: " + id);
        }
    }

    public static class CodeAlreadyUsedException extends ConflictException {
        public CodeAlreadyUsedException(String code) {
            super("Organization unit code already in use: " + code);
        }
    }
}
