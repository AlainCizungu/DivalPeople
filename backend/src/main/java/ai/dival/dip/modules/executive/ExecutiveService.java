package ai.dival.dip.modules.executive;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.modules.tix.DebtRecordService;
import ai.dival.dip.modules.tix.ExchangeService;
import ai.dival.dip.modules.tix.SubjectRightsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The view for somebody who does not work the queues.
 *
 * <p>The front door answers "what needs a person today". This answers the two questions asked one
 * floor up and one quarter apart: <em>is this working</em>, and <em>are we meeting the obligation
 * we took on</em>. They are different questions and they want a different shape — a screen
 * organised around today's queue is the wrong instrument for a board paper, and a board paper made
 * by squinting at today's queue is how a number gets quoted that nobody can source.
 *
 * <p><strong>Nothing here is modelled, projected or benchmarked.</strong> That constraint is the
 * whole design. An executive screen is where invented metrics go to live, because the audience is
 * furthest from the data and least able to challenge a figure — and a platform whose entire
 * argument is that its numbers can be checked cannot afford one page where they cannot. Every
 * figure below is a count of rows somebody could go and look at.
 *
 * <p><strong>The one honest trend line, and why there is only one.</strong> The platform snapshots
 * nothing: there is no record of what was owed last March, so a chart of exposure over time could
 * only be reconstructed by assuming records existed continuously from their default date, which is
 * an assumption dressed as history. What <em>is</em> recorded, every time, is that something
 * happened — a declaration written, an inquiry asked. So the activity series counts events, which
 * is evidence, and the exposure figure is stated as of today only.
 *
 * <p><strong>What is deliberately absent, and stated on the screen.</strong> The question an
 * executive actually wants — <em>how many of our inquiries came back with a debt</em> — cannot be
 * answered, because the audit trail records that an inquiry was made and what it was for, never
 * what it returned. That was a decision rather than an oversight: an audit row carrying the answer
 * would be a second copy of the disclosure, kept under the audit period rather than the retention
 * period, and would quietly turn the trail into a searchable shadow of the exchange. The screen
 * says so rather than leaving a reader to wonder why an obvious figure is missing.
 *
 * <p>Counts nothing itself, like the front door and for the same reason: each module answers for
 * its own figures, and this class decides who may see them and over what horizon.
 */
@Service
public class ExecutiveService {

    /**
     * How far back the activity series runs.
     *
     * <p>Thirteen months rather than twelve, so that the current month can be compared with the
     * same month a year ago without the chart running out at exactly the point somebody wants to
     * look. The extra month costs one row.
     */
    private static final int ACTIVITY_MONTHS = 13;

    private final DebtRecordService debtRecords;
    private final SubjectRightsService rights;
    private final AuditService audit;
    private final Clock clock;

    public ExecutiveService(DebtRecordService debtRecords, SubjectRightsService rights,
                            AuditService audit, Clock clock) {
        this.debtRecords = debtRecords;
        this.rights = rights;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param canDeclare  whether the caller may see the operator's own book and its activity
     * @param canSeeCases whether the caller may see how rights requests were answered
     */
    @Transactional(readOnly = true)
    public Briefing forCaller(boolean canDeclare, boolean canSeeCases) {
        LocalDate today = LocalDate.now(clock);
        Instant since = YearMonth.from(today).minusMonths(ACTIVITY_MONTHS - 1L)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Null rather than zero for a section the caller may not see, and never queried at all.
        // The front door settled this argument: nought is a fact somebody can act on, and nought
        // because you are not allowed to know is a different statement wearing the same digit.
        return new Briefing(
                today,
                canDeclare ? debtRecords.register(today, today) : null,
                canDeclare ? activity(since, today) : null,
                canSeeCases ? rights.record() : null);
    }

    /**
     * Thirteen months of what this operator did, with the empty months present.
     *
     * <p>A month in which nothing happened is a row of zeroes rather than a gap. A series that
     * skipped its quiet months would draw a line straight from March to September and read as
     * steady activity through a summer when the operator sent nothing — which is the opposite of
     * what happened, rendered more reassuringly than the truth.
     */
    private List<Month> activity(Instant since, LocalDate today) {
        return series(YearMonth.from(today),
                debtRecords.declarationsByMonth(since),
                audit.countByMonth(ExchangeService.INQUIRY_ACTION,
                        AuditService.OUTCOME_SUCCESS, since),
                audit.countByMonth(ExchangeService.INQUIRY_ACTION,
                        AuditService.OUTCOME_DENIED, since));
    }

    /**
     * The three grouped queries, arranged into a fixed window.
     *
     * <p>Static and taking its inputs, so the arranging can be tested without a database and
     * without a mock — this codebase has neither anywhere, and the shaping is the only part of this
     * class that can be wrong in a way a screen would not show. Which months exist, and whether a
     * silent month is a zero or a gap, is exactly the kind of mistake that renders beautifully and
     * still misleads a board.
     */
    static List<Month> series(YearMonth end, List<Object[]> declaredRows,
                              List<Object[]> askedRows, List<Object[]> refusedRows) {
        Map<YearMonth, Long> declared = byMonth(declaredRows);
        Map<YearMonth, Long> asked = byMonth(askedRows);
        Map<YearMonth, Long> refused = byMonth(refusedRows);

        List<Month> series = new ArrayList<>(ACTIVITY_MONTHS);
        YearMonth cursor = end.minusMonths(ACTIVITY_MONTHS - 1L);
        for (int i = 0; i < ACTIVITY_MONTHS; i++) {
            series.add(new Month(cursor.toString(),
                    declared.getOrDefault(cursor, 0L),
                    asked.getOrDefault(cursor, 0L),
                    refused.getOrDefault(cursor, 0L)));
            cursor = cursor.plusMonths(1);
        }
        return List.copyOf(series);
    }

    /**
     * Turns a grouped query's rows into months.
     *
     * <p><strong>The month arrives as {@code YYYY-MM} text, and both halves of that are the fix for
     * a defect this shipped with.</strong>
     *
     * <p>The first version selected {@code date_trunc} and cast the first column to
     * {@code java.sql.Timestamp}. Two things wrong with it, and the unit tests could not see
     * either, because a fixture supplies whatever type the fixture chooses. The driver's type for a
     * truncated {@code timestamptz} is not this code's to assume, and casting to the wrong one is a
     * {@code ClassCastException} on the first real request. And {@code date_trunc} over a
     * {@code timestamptz} truncates <em>in the session time zone</em> — so a deployment whose
     * database session was not UTC would have bucketed the last evening of every month into the
     * next one, quietly, while a test asserting UTC bucketing passed.
     *
     * <p>{@code at time zone 'UTC'} makes the truncation zone-free and agrees with the
     * application's {@code Clock.systemUTC()}. {@code to_char} then hands back a string, which has
     * exactly one representation and needs no cast.
     */
    private static Map<YearMonth, Long> byMonth(List<Object[]> rows) {
        Map<YearMonth, Long> counted = new java.util.HashMap<>();
        for (Object[] row : rows) {
            counted.merge(YearMonth.parse((String) row[0]),
                    ((Number) row[1]).longValue(), Long::sum);
        }
        return counted;
    }

    /**
     * @param asOf     the day the figures were counted on, so a stale tab is obvious
     * @param book     the operator's own register today, or null when they may not see it
     * @param activity thirteen months of events, oldest first, or null
     * @param rights   how requests from people were answered, or null
     */
    public record Briefing(LocalDate asOf,
                           DebtRecordService.RegisterSummary book,
                           List<Month> activity,
                           SubjectRightsService.RightsRecord rights) {
    }

    /**
     * @param month    ISO year and month, {@code 2026-08}, formatted where the words are
     * @param declared records this operator added to the registry
     * @param inquiries questions it asked the exchange and had answered
     * @param refused  questions the rate limiter turned down. Shown beside the answered ones
     *                 rather than hidden, because a month with a hundred refusals is either an
     *                 allowance set too low for how the institution works or a sweep somebody
     *                 should ask about, and both want a person to look
     */
    public record Month(String month, long declared, long inquiries, long refused) {
    }
}
