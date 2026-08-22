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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
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

    /**
     * How many subjects one sweep checks.
     *
     * <p><strong>A sweep is a slice, not a pass over everything.</strong> Every check is a real
     * inquiry — charged to the hourly allowance, rate-limited, audited — because a monitoring path
     * that reached the exchange without spending the allowance would be a way to query it with the
     * throttle off, and it would look identical from the outside.
     *
     * <p>So a watchlist larger than the allowance cannot be swept in one night, and pretending
     * otherwise would mean either exempting monitoring from the limit or silently dropping the
     * remainder. It takes the stalest rows each night and continues tomorrow: every subject is
     * reached in turn, none is starved, and the arithmetic is visible to whoever sizes a
     * deployment rather than buried.
     */
    private static final int SWEEP_SLICE = 100;

    /**
     * How many open alerts one screen carries.
     *
     * <p>An operator with a large watchlist and a bad month can have thousands. Pulling them all to
     * render a queue somebody works fifty at a time is the mistake the dashboard already made, and
     * the count beside the list comes from the database rather than from this array's length.
     */
    private static final int MAX_OPEN_ALERTS = 200;

    private final WatchlistRepository watches;
    private final WatchlistGroupRepository groups;
    private final MonitoringAlertRepository alerts;
    private final SubjectRepository subjects;
    private final ExchangeService exchange;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public WatchlistService(WatchlistRepository watches, WatchlistGroupRepository groups,
                            MonitoringAlertRepository alerts, SubjectRepository subjects,
                            ExchangeService exchange, NotificationService notifications,
                            AuditService audit, Clock clock) {
        this.watches = watches;
        this.groups = groups;
        this.alerts = alerts;
        this.subjects = subjects;
        this.exchange = exchange;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The watches this operator holds, already flattened.
     *
     * <p>Returns a record rather than the entity, and that is the fix for a mistake this codebase
     * has now made twice. The controller was mapping the list after this method returned — outside
     * the transaction, on a closed session — so every {@code entry.getSubject()} was a lazy proxy
     * and the first name read threw. {@code BatchResponse} had exactly this shape and cost a round
     * trip to diagnose.
     *
     * <p>The rule that avoids it: a service that owns a transaction hands back data, not entities
     * that still need one.
     */
    @Transactional(readOnly = true)
    public List<Watch> list() {
        return watches.findByTenantIdOrderByExpiresAt(TenantContext.require()).stream()
                .map(entry -> new Watch(entry.getId(), entry.getSubject().getId(),
                        entry.getSubject().getFullName(), entry.getPurpose(),
                        entry.getExpiresAt(), entry.getLastOutcome(),
                        entry.getLastInstitutions(), entry.getLastScore(),
                        entry.getLastCheckedAt(),
                        entry.getWatchlist() == null ? null : entry.getWatchlist().getId(),
                        entry.getWatchlist() == null ? null : entry.getWatchlist().getName()))
                .toList();
    }

    /** A watch as the operator sees it: an outcome and a count, exactly as an inquiry discloses. */
    /**
     * @param lastScore     the indicator at the last sweep, or null — never known, or the exchange
     *                      would not confirm the identity that night
     * @param watchlistId   the group, or null for an unfiled watch. Null is a real state and the
     *                      screen names it rather than hiding those rows
     */
    public record Watch(UUID id, UUID subjectId, String name, String purpose, Instant expiresAt,
                        InquiryResult.Outcome lastOutcome, Integer lastInstitutions,
                        Integer lastScore, Instant lastCheckedAt,
                        UUID watchlistId, String watchlistName) {
    }

    /**
     * The groups, each with what is in it.
     *
     * <p>Counted in the database rather than by loading the entries. A bank monitoring twelve
     * thousand customers would otherwise pull twelve thousand rows to render six numbers, which is
     * the mistake the dashboard already made once.
     */
    @Transactional(readOnly = true)
    public List<Group> groups() {
        UUID tenantId = TenantContext.require();
        List<Group> listed = new ArrayList<>();
        for (Watchlist group : groups.findByTenantIdOrderByName(tenantId)) {
            listed.add(new Group(group.getId(), group.getName(), group.getPurpose(),
                    (int) watches.countByTenantIdAndWatchlistId(tenantId, group.getId())));
        }
        // Unfiled last, and present even when empty is false — a zero here would be a group nobody
        // made, and a row that appears from nowhere the first time somebody forgets to file a
        // watch is more confusing than one that is simply absent.
        int unfiled = (int) watches.countByTenantIdAndWatchlistIsNull(tenantId);
        if (unfiled > 0) {
            listed.add(new Group(null, null, null, unfiled));
        }
        return List.copyOf(listed);
    }

    /**
     * Creates a group.
     *
     * @param purpose why the group exists, required for the same reason a watch needs one:
     *                monitoring a list of companies indefinitely with no stated reason is the thing
     *                a regulator objects to
     */
    @Transactional
    public Group createGroup(String name, String purpose, UUID actorId) {
        if (name == null || name.isBlank()) {
            throw new PolicyRefusedException("A watchlist needs a name.");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new PolicyRefusedException(
                    "Say what this watchlist is for. A standing list of companies with no stated "
                            + "reason is the thing a regulator asks about first.");
        }
        Watchlist group = groups.save(new Watchlist(name.trim(), purpose.trim(), actorId));
        audit.record("TIX_WATCHLIST_CREATED", "Watchlist", group.getId().toString(),
                AuditService.OUTCOME_SUCCESS, actorId, name.trim() + ": " + purpose.trim());
        return new Group(group.getId(), group.getName(), group.getPurpose(), 0);
    }

    /**
     * Moves a watch into a group, or out of every group.
     *
     * <p>A null group unfiles it rather than stopping the watch. Filing is organisation; stopping
     * is a decision, and this method is not where that one gets made by accident.
     */
    @Transactional
    public void file(UUID watchId, UUID groupId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        WatchlistEntry entry = watches.findByIdAndTenantId(watchId, tenantId)
                .orElseThrow(() -> new WatchNotFoundException(watchId));

        Watchlist group = groupId == null ? null
                : groups.findByIdAndTenantId(groupId, tenantId)
                        .orElseThrow(() -> new WatchNotFoundException(groupId));

        entry.fileUnder(group);
        audit.record("TIX_WATCH_FILED", "Subject", entry.getSubject().getId().toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                group == null ? "unfiled" : "filed under " + group.getName());
    }

    /** @param id null for the unfiled pseudo-group, which is a real state and not a group */
    public record Group(UUID id, String name, String purpose, int watched) {
    }

    /**
     * The alert queue: what changed and has not been looked at.
     *
     * <p><strong>Sorted here rather than in the query.</strong> The severity column stores the enum
     * name, so ordering by it in SQL sorts alphabetically — INFORMATIONAL, MATERIAL, NOTABLE — and
     * puts the least urgent alerts at the top of a queue whose entire job is the opposite. It would
     * have looked like a working sort. In Java the order is the enum's own, which is declared
     * worst-first and can be read.
     */
    @Transactional(readOnly = true)
    public List<Alert> openAlerts() {
        return alerts.findOpen(TenantContext.require(), Limit.of(MAX_OPEN_ALERTS)).stream()
                .sorted(Comparator.comparing(MonitoringAlert::getSeverity)
                        .thenComparing(MonitoringAlert::getRaisedAt, Comparator.reverseOrder()))
                .map(WatchlistService::describe)
                .toList();
    }

    /**
     * Somebody looked at an alert and said what they concluded.
     *
     * <p>The note is required, exactly as it is on a dispute and on a resolution decision. An alert
     * closed with no reason tells a later reader nothing except that the queue got shorter, and the
     * queue getting shorter is not the outcome anybody wanted.
     */
    @Transactional
    public Alert acknowledge(UUID alertId, String note, UUID actorId) {
        if (note == null || note.isBlank()) {
            throw new PolicyRefusedException(
                    "Say what you found. An alert closed with no reason records only that somebody "
                            + "made the queue shorter.");
        }
        MonitoringAlert alert = alerts.findByIdAndTenantId(alertId, TenantContext.require())
                .orElseThrow(() -> new WatchNotFoundException(alertId));

        if (!alert.isOpen()) {
            throw new ConflictException("This alert has already been acknowledged.");
        }

        alert.acknowledge(actorId, note.trim(), clock.instant());
        audit.record("TIX_ALERT_ACKNOWLEDGED", "Subject",
                alert.getSubject().getId().toString(), AuditService.OUTCOME_SUCCESS, actorId,
                note.trim());
        return describe(alert);
    }

    private static Alert describe(MonitoringAlert alert) {
        return new Alert(alert.getId(), alert.getSubject().getId(),
                alert.getSubject().getFullName(), alert.getRaisedAt(), alert.getSeverity(),
                alert.getPreviousOutcome(), alert.getCurrentOutcome(),
                alert.getPreviousInstitutions(), alert.getCurrentInstitutions(),
                alert.getPreviousScore(), alert.getCurrentScore(),
                alert.getAcknowledgedAt(), alert.getAcknowledgementNote());
    }

    /**
     * One change, with what it was before.
     *
     * <p>Carries no amount and names no institution. Which participant began reporting and how much
     * they are owed are the exchange's standing refusals, and neither becomes disclosable because
     * it arrived as a change rather than as an answer.
     *
     * @param previousOutcome null when this was the subject's first observed state
     */
    public record Alert(UUID id, UUID subjectId, String name, Instant raisedAt,
                        MonitoringAlert.Severity severity,
                        InquiryResult.Outcome previousOutcome,
                        InquiryResult.Outcome currentOutcome,
                        Integer previousInstitutions, int currentInstitutions,
                        Integer previousScore, Integer currentScore,
                        Instant acknowledgedAt, String acknowledgementNote) {
    }

    /**
     * Starts watching a company.
     *
     * <p>Refuses without a purpose, exactly as a single inquiry does. The wording of the refusal
     * matters more here than there: an inquiry is one question on one afternoon, and a watch is a
     * decision to keep asking for a year.
     */
    @Transactional
    public Watch watch(UUID subjectId, String purpose, UUID actorId) {
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
        return new Watch(entry.getId(), subject.getId(), subject.getFullName(),
                entry.getPurpose(), entry.getExpiresAt(), null, null, null);
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

        long live = watches.countByTenantIdAndExpiresAtAfter(tenantId, now);
        List<WatchlistEntry> slice =
                watches.findDueForSweep(tenantId, now, Limit.of(SWEEP_SLICE));
        List<UUID> changed = new ArrayList<>();

        for (WatchlistEntry entry : slice) {
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

            // Null rather than zero when the exchange withheld the indicator. It withholds one for
            // any answer it is not confident about, and recording that as a score of nought would
            // make the next sweep read a recovery from a company that never moved.
            Integer score = answer.indicator() == null ? null : answer.indicator().score();

            WatchlistEntry.Change change = entry.observe(
                    answer.outcome(), answer.institutionCount(), score, now);

            if (change.isSomething()) {
                changed.add(subject.getId());
                raise(entry, subject, change, now, actorId);
            }
        }

        audit.record("TIX_WATCH_SWEPT", "Subject", null, AuditService.OUTCOME_SUCCESS, actorId,
                slice.size() + " of " + live + " watch(es) checked, " + changed.size()
                        + " changed");
        return new Sweep((int) live, slice.size(), changed.size());
    }

    /**
     * Writes the alert, and tells whoever opened the watch.
     *
     * <p>Both, and they are not the same thing. The notification is how somebody finds out today;
     * the alert is what an institution produces in a year when asked to show what it knew and
     * when. A notification cannot serve as the second — it is a sentence sent once, with no record
     * of what the figures had been before it.
     *
     * <p>The alert is written first. If the notification fails there is still a record of the
     * change; the other order would lose the evidence and keep the nudge.
     */
    private void raise(WatchlistEntry entry, Subject subject, WatchlistEntry.Change change,
                       Instant now, UUID actorId) {
        MonitoringAlert.Severity severity = ChangeGrading.grade(change);

        alerts.save(new MonitoringAlert(entry, subject, now,
                change.previousOutcome(), change.currentOutcome(),
                change.previousInstitutions(), change.currentInstitutions(),
                change.previousScore(), change.currentScore(), severity));

        audit.record("TIX_MONITORING_ALERT", "Subject", subject.getId().toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                severity + ": " + change.previousOutcome() + " to " + change.currentOutcome()
                        + ", institutions " + change.previousInstitutions() + " to "
                        + change.currentInstitutions() + ", score " + change.previousScore()
                        + " to " + change.currentScore());

        if (entry.getAddedBy() == null) {
            return;
        }
        notifications.notify(entry.getAddedBy(), "watchedSubjectChanged",
                Map.of("name", subject.getFullName(),
                        "outcome", change.currentOutcome().name(),
                        "institutions", String.valueOf(change.currentInstitutions())),
                severity == MonitoringAlert.Severity.MATERIAL
                        ? Notification.Severity.WARNING
                        : Notification.Severity.INFO,
                "Subject", subject.getId().toString());
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

    /**
     * What one night's slice did.
     *
     * @param watched how many live watches this operator holds in total
     * @param checked how many of them this sweep actually reached. Less than {@code watched} when
     *                the list is larger than a slice, which is the honest shape: every check is a
     *                charged inquiry, so a big watchlist takes several nights and the screen says
     *                so rather than implying a full pass
     * @param changed how many answers differ from the last sweep, which is what gets told
     */
    public record Sweep(int watched, int checked, int changed) {
    }

    /** Deliberately does not reveal whether the watch exists under another operator. */
    public static class WatchNotFoundException extends ResourceNotFoundException {
        public WatchNotFoundException(UUID id) {
            super("Watch not found: " + id);
        }
    }
}
