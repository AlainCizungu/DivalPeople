package ai.dival.dip.common.audit;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes audit events.
 *
 * <p>Events are written in their own transaction so that an audit entry survives the rollback of
 * the operation that produced it. A denied or failed attempt is exactly the thing an auditor
 * most wants to see.
 *
 * <p>The caller's address and request id are filled in here rather than passed by every call site.
 * They used to be hardcoded {@code null} in both methods, despite the columns existing since the
 * first migration and {@code application-prod.yml} carrying a comment explaining why the forwarded
 * headers matter. Forty-odd call sites would each have had to remember; this way none of them can
 * forget.
 */
@Service
public class AuditService {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_DENIED = "DENIED";
    public static final String OUTCOME_FAILURE = "FAILURE";

    /** The most rows one request may pull back. A page, not an export. */
    private static final int MAX_TRAIL_PAGE = 500;

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String outcome,
                       UUID actorId) {
        record(action, resourceType, resourceId, outcome, actorId, null);
    }

    /**
     * Records an event along with why it was done.
     *
     * @param detail the actor's stated reason, where the API asks for one. Free text, stored and
     *               never parsed. This exists because a TIX inquiry validates a {@code purpose}
     *               and then threw it away, which left the exchange's only accountability control
     *               as a row saying somebody looked at somebody.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String outcome,
                       UUID actorId, String detail) {
        repository.save(new AuditEvent(
                TenantContext.find().orElse(null),
                actorId,
                action,
                resourceType,
                resourceId,
                outcome,
                currentRequestId(),
                currentIpAddress(),
                truncate(detail),
                Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String action, String resourceType, String resourceId, UUID actorId) {
        record(action, resourceType, resourceId, OUTCOME_SUCCESS, actorId);
    }

    /**
     * The current tenant's own trail, newest first.
     *
     * <p>Tenant-scoped, which is the whole of the access model: an operator reads what its own
     * staff did and nothing else. The rows themselves are already confined that way — {@code
     * audit_event} has carried a row-level security policy since V20 — so this is the application
     * agreeing with the database rather than substituting for it.
     *
     * @param action null for everything; an exact action name to narrow to one kind of event
     * @param limit  clamped, because the caller is a page and a page cannot render a hundred
     *               thousand rows however sincerely it asks for them
     */
    @Transactional(readOnly = true)
    public List<AuditEvent> recent(String action, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_TRAIL_PAGE));
        return repository.findRecent(
                TenantContext.require(),
                action == null || action.isBlank() ? null : action,
                PageRequest.of(0, capped));
    }

    /**
     * How often each action appears in this tenant's trail.
     *
     * <p>Counted over the whole trail rather than over the page above, deliberately. The number
     * worth showing an auditor is how many inquiries an operator has ever made, not how many are
     * on the screen.
     */
    @Transactional(readOnly = true)
    public List<ActionCount> countsByAction() {
        List<ActionCount> counts = new ArrayList<>();
        for (Object[] row : repository.countByAction(TenantContext.require())) {
            counts.add(new ActionCount((String) row[0], ((Number) row[1]).longValue()));
        }
        return List.copyOf(counts);
    }

    public record ActionCount(String action, long count) {
    }

    /**
     * Who, in the current tenant, was successfully served this resource since a moment.
     *
     * <p>The audit trail's first read path, and it exists because of an obligation rather than a
     * feature request: article 214 of the Code du numérique requires that a rectification or an
     * erasure be communicated to the parties the incorrect data was communicated to, and nothing
     * else in the platform remembers who those were.
     *
     * <p>That makes the trail load-bearing in a new way. Until now it was evidence — written, kept,
     * and read only when somebody asked a question. From here a gap in it is a person who does not
     * get told that the record they refused credit on was wrong.
     *
     * <p><strong>Not {@code REQUIRES_NEW}</strong>, unlike every write above. Those suspend the
     * caller's transaction so that a rolled-back operation still leaves its audit row; a read has
     * no such need and joins the caller instead, so it sees the tenant the caller has bound.
     */
    @Transactional(readOnly = true)
    public List<UUID> actorsServed(String action, String resourceType, String resourceId,
                                   Instant since) {
        return repository.findDistinctActors(TenantContext.require(), action, resourceType,
                resourceId, OUTCOME_SUCCESS, since);
    }

    /** Null outside a request — scheduled work and seeders audit legitimately, with no caller. */
    private String currentRequestId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        Object id = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return id == null ? null : id.toString();
    }

    /**
     * The client's address as the proxy reports it.
     *
     * <p>{@code forward-headers-strategy: framework} makes Spring rewrite the request from the
     * forwarded headers before anything reads it, so this is the real client rather than Caddy.
     * Behind a proxy that does <em>not</em> set those headers this records the proxy, which is
     * wrong but not misleading — every row would say the same thing.
     */
    private String currentIpAddress() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String address = request.getRemoteAddr();
        // The column is 45 characters, which is the longest an IPv6 address with an embedded IPv4
        // suffix can be. Anything longer is not an address.
        return address != null && address.length() <= 45 ? address : null;
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest()
                : null;
    }

    /**
     * Bounded, so a caller cannot use the audit log as storage.
     *
     * <p>Truncated rather than rejected: refusing to write the event because its reason was too
     * long would lose the event, and losing an audit row to protect a column width is the wrong
     * trade in every direction.
     */
    private String truncate(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String trimmed = detail.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 497) + "...";
    }
}
