package ai.dival.dip.common.audit;

import ai.dival.dip.common.security.Roles;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the trail.
 *
 * <p>The platform has written audit rows since the first migration and has never once shown them
 * to anybody. That is a strange kind of control: the landing page tells institutions every inquiry
 * is recorded with its stated purpose, and until now the only way to check was to open a database
 * console. A claim about accountability that nobody can inspect is a claim about nothing.
 *
 * <p><strong>Restricted, and to three roles rather than one.</strong> An auditor reads the trail
 * because that is the job; a compliance officer because they answer for what is in it; a tenant
 * administrator because they are accountable for their own staff. An ordinary declarant or
 * inquirer is not on the list — the trail records what they did, and somebody watching their own
 * watchers is not oversight.
 *
 * <p>Tenant-scoped twice over: the query filters on the tenant and {@code audit_event} has carried
 * a row-level security policy since V20. Neither is redundant. The policy is what holds if this
 * class is ever wrong.
 *
 * <p>Lives in {@code common} rather than a module because the trail is not any one module's — the
 * ingest, tix and notification modules all write to it. That is also why the actor is returned as
 * an id and never as a name: resolving one would mean {@code common} importing the users module,
 * and rule 1 of the architecture check exists to stop exactly that. The screen says plainly that
 * the value is an account id.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final int DEFAULT_LIMIT = 100;

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/events")
    @PreAuthorize("hasAnyRole('" + Roles.AUDITOR + "', '" + Roles.COMPLIANCE_OFFICER
            + "', '" + Roles.TENANT_ADMIN + "')")
    public List<Entry> events(@RequestParam(name = "action", required = false) String action,
                              @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return audit.recent(action, limit <= 0 ? DEFAULT_LIMIT : limit).stream()
                .map(Entry::from)
                .toList();
    }

    /** How many of each action this operator has recorded, over the whole trail. */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('" + Roles.AUDITOR + "', '" + Roles.COMPLIANCE_OFFICER
            + "', '" + Roles.TENANT_ADMIN + "')")
    public List<AuditService.ActionCount> summary() {
        return audit.countsByAction();
    }

    /**
     * One event, as shown.
     *
     * <p>The entity is never serialised directly, and the projection is not merely a habit here:
     * {@code tenantId} is deliberately absent. Every row a caller can see belongs to their own
     * tenant, so echoing it back says nothing — and a field that always holds the reader's own id
     * is the kind of thing a later change quietly repurposes.
     *
     * @param detail  the actor's stated reason where the API asked for one. For a TIX inquiry this
     *                is the purpose, and it is the single most valuable column here: it is what
     *                turns "somebody looked this person up" into something answerable.
     * @param actorId an account id, not a name. See the note on this class.
     */
    public record Entry(UUID id, String action, String resourceType, String resourceId,
                        String outcome, UUID actorId, String requestId, String ipAddress,
                        String detail, Instant occurredAt) {

        static Entry from(AuditEvent event) {
            return new Entry(
                    event.getId(),
                    event.getAction(),
                    event.getResourceType(),
                    event.getResourceId(),
                    event.getOutcome(),
                    event.getActorId(),
                    event.getRequestId(),
                    event.getIpAddress(),
                    event.getDetail(),
                    event.getOccurredAt());
        }
    }
}
