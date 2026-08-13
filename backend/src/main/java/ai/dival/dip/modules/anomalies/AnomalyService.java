package ai.dival.dip.modules.anomalies;

import ai.dival.dip.common.audit.AuditEventRepository;
import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tix.ExchangeService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform watching how it is used.
 *
 * <p>Everything else in DIP checks whether a record is right. This checks whether the asking is
 * right, which is the one question an operator cannot answer for itself and the one the audit
 * trail was built for. The exchange has recorded every inquiry with its actor, its outcome and a
 * stated purpose since the rate limiter went in, on the argument that a throttled sweep which left
 * no trace would simply be a slower invisible sweep. Nothing has ever read those rows back. This
 * does.
 *
 * <p><strong>What it deliberately does not do.</strong> There is no list of who looked up whom.
 * That would be a second copy of the audit trail with a worse justification, and the interesting
 * question is about the caller rather than about the people they called about.
 *
 * <p><strong>And what it cannot do.</strong> The fraud signal this module was expected to surface —
 * one identifier under two subjects — can never fire, and not because it is broken. Both unique
 * indexes on {@code tix_subject_identifier} make it impossible: a national document resolves to one
 * subject by construction, which is how identity resolution works at all. An alert for a thing the
 * database prevents would be a permanently green light, and a permanently green light is worse than
 * no light.
 */
@Service
public class AnomalyService {

    /**
     * How far back the behaviour window looks.
     *
     * <p>Seven days. Long enough that a Tuesday afternoon of ordinary work does not dominate,
     * short enough that a sweep three weeks ago is not still being reported as though it were
     * happening now — the point is to notice while somebody can still be telephoned about it.
     */
    private static final int WINDOW_DAYS = 7;

    /**
     * How much busier than the institution's own median counts as unusual.
     *
     * <p>Relative rather than absolute, because a bank's call centre and a two-person microfinance
     * have nothing in common and any constant would be wrong for both. Four times the median is a
     * long way clear of ordinary variation between colleagues doing the same job.
     */
    private static final double VOLUME_MULTIPLE = 4.0;

    /** Below this many inquiries, one person's afternoon is not a pattern. */
    private static final long VOLUME_FLOOR = 20;

    /**
     * The proportion of inquiries finding nobody that starts to look like walking a format.
     *
     * <p>High on purpose. An operator checking a list of applicants legitimately misses often —
     * most people are not in a bad-payer registry, which is the point of it — so a modest miss
     * rate is the normal state and flagging it would flag everybody.
     */
    private static final double NO_MATCH_RATIO = 0.9;

    private final AuditEventRepository events;
    private final AuditService audit;
    private final Clock clock;

    public AnomalyService(AuditEventRepository events, AuditService audit, Clock clock) {
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report forOperator(UUID actorId) {
        UUID tenantId = TenantContext.require();
        Instant since = clock.instant().minus(WINDOW_DAYS, ChronoUnit.DAYS);

        List<Object[]> rows = events.inquiryBehaviourSince(
                tenantId, ExchangeService.INQUIRY_ACTION, AuditService.OUTCOME_DENIED, since);

        List<Raw> raw = new ArrayList<>();
        for (Object[] row : rows) {
            raw.add(new Raw((UUID) row[0], count(row[1]), count(row[2]), count(row[3]),
                    (Instant) row[4]));
        }

        long median = medianInquiries(raw);
        List<InquiryBehaviour> people = raw.stream()
                .map(person -> new InquiryBehaviour(person.actorId(), person.inquiries(),
                        person.noMatch(), person.refused(), person.lastAsked(),
                        flagsFor(person, median)))
                .sorted(Comparator
                        .comparingInt((InquiryBehaviour b) -> b.flags().size()).reversed()
                        .thenComparing(Comparator.comparingLong(InquiryBehaviour::inquiries)
                                .reversed()))
                .toList();

        // Reading how colleagues have been using the platform is itself a thing somebody should
        // be accountable for, so looking is recorded like everything else.
        audit.record("TIX_BEHAVIOUR_REVIEWED", "AuditEvent", null,
                AuditService.OUTCOME_SUCCESS, actorId,
                people.size() + " user(s) over " + WINDOW_DAYS + " day(s)");

        return new Report(WINDOW_DAYS, median, List.copyOf(people));
    }

    private List<BehaviourFlag> flagsFor(Raw person, long median) {
        List<BehaviourFlag> flags = new ArrayList<>();
        if (person.inquiries() >= VOLUME_FLOOR && median > 0
                && person.inquiries() > median * VOLUME_MULTIPLE) {
            flags.add(BehaviourFlag.HIGH_VOLUME);
        }
        // The floor applies here too. Three inquiries that all missed is a person who had a quiet
        // morning, and calling it a sweep would train whoever reads this to ignore the screen.
        if (person.inquiries() >= VOLUME_FLOOR
                && (double) person.noMatch() / person.inquiries() >= NO_MATCH_RATIO) {
            flags.add(BehaviourFlag.MOSTLY_NO_MATCH);
        }
        if (person.refused() > 0) {
            flags.add(BehaviourFlag.HIT_THE_RATE_LIMIT);
        }
        return flags;
    }

    /**
     * The institution's own middle, which is what everything is measured against.
     *
     * <p>Median rather than mean, because the mean is dragged by exactly the person the screen is
     * looking for: one heavy user raises the average and thereby raises their own threshold.
     */
    private long medianInquiries(List<Raw> raw) {
        if (raw.isEmpty()) {
            return 0;
        }
        List<Long> counts = raw.stream().map(Raw::inquiries).sorted().toList();
        int middle = counts.size() / 2;
        return counts.size() % 2 == 1
                ? counts.get(middle)
                : (counts.get(middle - 1) + counts.get(middle)) / 2;
    }

    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private record Raw(UUID actorId, long inquiries, long noMatch, long refused,
                       Instant lastAsked) {
    }

    /**
     * @param windowDays how far back this looked
     * @param medianInquiries the institution's own middle, published so a reader can see what
     *                        "unusual" was measured against rather than take the word for it
     */
    public record Report(int windowDays, long medianInquiries, List<InquiryBehaviour> people) {
    }
}
