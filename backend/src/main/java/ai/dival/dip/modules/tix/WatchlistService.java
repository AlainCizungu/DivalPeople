package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watching a company, which is a standing inquiry and is charged like one.
 *
 * <p>An operator can already ask the exchange about any subject. A watchlist is not a new power;
 * it is that question asked on a schedule, and everything below exists to stop it becoming more.
 *
 * <p><strong>Every sweep is a real inquiry.</strong> It goes through {@link ExchangeService}, which
 * charges the rate limiter, writes the audit row under the same action with the same stated
 * purpose, and returns exactly what a person clicking the inquiry button would have received. An
 * operator watching two hundred companies spends two hundred inquiries a night against the same
 * allowance as anybody else — which is the point. A watchlist that bypassed the limit would be a
 * way to sweep the exchange with the throttle removed and the trail thinned.
 *
 * <p><strong>And it is answered nightly rather than immediately.</strong> A notification the same
 * afternoon a rival declares would disclose timing, and timing plus a count of two is an
 * attribution by elimination: the watcher knows the other institution is not itself. Arriving on a
 * schedule nobody controls, the count carries no such inference.
 */
@Service
public class WatchlistService {

    /**
     * How long a watch lasts before somebody has to want it again.
     *
     * <p>Twelve months. Long enough to be useful for a credit relationship, short enough that a
     * list of companies nobody remembers adding stops being monitored — the alternative is
     * surveillance by accretion, where nothing is ever decided and nothing ever stops.
     */
    private static final int WATCH_MONTHS = 12;

    /** More than this and it is a feed rather than a watchlist. */
    private static final int MAX_WATCHES = 200;

    private final WatchlistRepository watches;
    private final SubjectRepository subjects;
    private final ExchangeService exchange;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public WatchlistService(WatchlistRepository watches, SubjectRepository subjects,
                            ExchangeService exchange, NotificationService notifications,
                            AuditService audit, Clock clock) {
        this.watches = watches;
        this.subjects = subjects;
        this.exchange = exchange;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WatchlistEntry> list() {
        return watches.findByTenantIdOrderByExpiresAt(TenantContext.require());
    }

    /**
     * Starts watching a company.
     *
     * <p>Refuses without a purpose, exactly as a single inquiry does. The wording of the refusal
     * matters more here than there: an inquiry is one question on one afternoon, and a watch is a
     * decision to keep asking for a year.
     */
    @Transactional
    public WatchlistEntry watch(UUID subjectId, String purpose, UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (purpose == null || purpose.isBlank()) {
            throw new PolicyRefusedException(
                    "Say why this company is being monitored. A watch asks the exchange about "
                            + "somebody every night for a year, and \"we always have\" is not a "
                            + "reason anybody can defend later.");
        }
        if (watches.countByTenantIdAndExpiresAtAfter(tenantId, clock.instant()) >= MAX_WATCHES) {
            throw new PolicyRefusedException(
                    "You are already watching " + MAX_WATCHES + " companies, which is as many as "
                            + "a person can act on. Let some expire, or ask about the rest when "
                            + "you need to.");
        }
        watches.findByTenantIdAndSubjectId(tenantId, subjectId).ifPresent(existing -> {
            throw new ConflictException("You are already watching this company.");
        });

        Subject subject = subjects.findById(subjectId)
                .orElseThrow(() -> new WatchNotFoundException(subjectId));

        WatchlistEntry entry = watches.save(new WatchlistEntry(subject, purpose.trim(), actorId,
                clock.instant().plus(WATCH_MONTHS * 30L, ChronoUnit.DAYS)));

        audit.record("TIX_WATCH_ADDED", "Subject", subjectId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, purpose.trim());
        return entry;
    }

    @Transactional
    public void unwatch(UUID watchId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        WatchlistEntry entry = watches.findByIdAndTenantId(watchId, tenantId)
                .orElseThrow(() -> new WatchNotFoundException(watchId));
        // Deleted rather than deactivated. A watch that has stopped holds no history worth
        // keeping — what it observed is in the audit trail, where it belongs — and a row that
        // says "not watching this any more" is personal data retained for no reason.
        watches.delete(entry);
        audit.record("TIX_WATCH_REMOVED", "Subject",
                entry.getSubject().getId().toString(), AuditService.OUTCOME_SUCCESS, actorId, null);
    }

    /**
     * Asks the exchange about everything being watched, and tells somebody what moved.
     *
     * <p>Meant to run nightly. Manual for now, deliberately: a job that charges an operator's rate
     * limit on a schedule nobody has watched run once is a job that gets discovered by somebody
     * being refused their own inquiries at nine in the morning.
     *
     * <p>The first sweep of a new watch is a baseline rather than a change. Announcing it would
     * fire a notification for every watch on the night it was created, which teaches whoever reads
     * them that the first one means nothing — and then that the rest might not either.
     */
    @Transactional
    public Sweep sweep(UUID actorId) {
        UUID tenantId = TenantContext.require();
        Instant now = clock.instant();

        List<WatchlistEntry> live = watches.findByTenantIdAndExpiresAtAfter(tenantId, now);
        List<UUID> changed = new ArrayList<>();

        for (WatchlistEntry entry : live) {
            Subject subject = entry.getSubject();

            // Through the exchange, not around it. This is where the rate limit is charged and the
            // audit row is written, and routing around either would make a watchlist a way to
            // sweep the exchange with the throttle off.
            //
            // Asked with the subject's own strongest document rather than its name alone, because
            // a name-only inquiry about a person scores below the automatic threshold and comes
            // back "review required" for ever. The watch would then report the same
            // non-answer every night and look broken while working exactly as specified.
            InquiryResult answer = exchange.inquire(
                    new InquiryRequest(strongestDocument(subject), subject.getFullName(),
                            entry.getPurpose()),
                    actorId);

            if (entry.observe(answer.outcome(), answer.institutionCount(), now)) {
                changed.add(subject.getId());
                if (entry.getAddedBy() != null) {
                    notifications.notify(entry.getAddedBy(), "watchedSubjectChanged",
                            Map.of("name", subject.getFullName(),
                                    "outcome", answer.outcome().name(),
                                    "institutions", String.valueOf(answer.institutionCount())),
                            answer.outcome() == InquiryResult.Outcome.OUTSTANDING_DEBT
                                    ? Notification.Severity.WARNING
                                    : Notification.Severity.INFO,
                            "Subject", subject.getId().toString());
                }
            }
        }

        audit.record("TIX_WATCH_SWEPT", "Subject", null, AuditService.OUTCOME_SUCCESS, actorId,
                live.size() + " watch(es), " + changed.size() + " changed");
        return new Sweep(live.size(), changed.size());
    }

    /**
     * The subject's own national document, if it has one.
     *
     * <p>National only. An account reference resolves inside the operator that issued it, so
     * asking the exchange with one would find the watcher's own record and nothing else — a watch
     * that answered itself.
     */
    private List<InquiryRequest.SubmittedIdentifier> strongestDocument(Subject subject) {
        return subject.getIdentifiers().stream()
                .filter(identifier -> identifier.getIdentifierType().isStrong()
                        && !identifier.getIdentifierType().isOperatorScoped())
                .findFirst()
                .map(identifier -> List.of(new InquiryRequest.SubmittedIdentifier(
                        identifier.getIdentifierType(), identifier.getNormalizedValue())))
                .orElseGet(List::of);
    }

    /** @param changed how many answers differ from the last sweep, which is what gets told */
    public record Sweep(int watched, int changed) {
    }

    /** Deliberately does not reveal whether the watch exists under another operator. */
    public static class WatchNotFoundException extends ResourceNotFoundException {
        public WatchNotFoundException(UUID id) {
            super("Watch not found: " + id);
        }
    }
}
