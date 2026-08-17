package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finding a business in your own book, and seeing everything you hold about it.
 *
 * <p><strong>Read the tenant scoping before adding anything here.</strong> A subject is shared —
 * {@code tix_subject} has no {@code tenant_id}, because several operators declare against the same
 * person, and that is the whole point of an exchange. It follows that a search which begins at the
 * subject table searches the national registry. One participant could then type a letter and list
 * every business its competitors have reported, which is not a privacy footnote: it is the reason
 * a second telecom would decline to join.
 *
 * <p>So every query here starts from {@code tix_debt_record} and filters on the calling tenant.
 * An operator finds only people it already holds a record against. What it then sees about them is
 * its own data, in full, because withholding an operator's own figures from it protects nobody.
 *
 * <p>Nothing here crosses the boundary. Learning what <em>other</em> operators hold is
 * {@link ExchangeService}, which takes an identifier and a stated purpose, is rate-limited, and
 * returns a status rather than a list.
 */
@Service
public class SearchService {

    /** Below this, a search matches most of the registry and is not a search. */
    private static final int MINIMUM_QUERY_LENGTH = 2;

    /** Enough for a person scanning a screen; past it, they should narrow the query. */
    private static final int MAX_RESULTS = 50;

    private final DebtRecordRepository debtRecords;
    private final AuditService audit;

    /**
     * The application's clock, not the machine's.
     *
     * <p>Injected in August 2026 to fix a defect these three methods shared: they called
     * {@code LocalDate.now()}, which reads the JVM's default zone, while the platform declares
     * {@code Clock.systemUTC()} as its clock and everything else asks that. On a server set to
     * anything but UTC the two disagree for part of every day — and both things "today" decides
     * here are consequential: whether a record has passed its retention date and is therefore
     * invisible, and which ageing band it falls in. A subject could be findable on one screen and
     * erased on another, for a few hours, on a machine nobody had thought about.
     */
    private final Clock clock;

    public SearchService(DebtRecordRepository debtRecords, AuditService audit, Clock clock) {
        this.debtRecords = debtRecords;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Businesses and people in this operator's own book, by name or by identifier.
     *
     * <p>The query is tried both ways at once rather than the caller choosing. A credit officer
     * with a scrap of paper does not know whether what is written on it is a trading name or a
     * register number, and making them declare it in a dropdown is asking them to solve the
     * problem they came here with.
     */
    @Transactional(readOnly = true)
    public List<Result> searchOwn(String query, UUID actorId) {
        UUID tenantId = TenantContext.require();
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < MINIMUM_QUERY_LENGTH) {
            throw new PolicyRefusedException(
                    "Type at least " + MINIMUM_QUERY_LENGTH + " characters. A shorter query "
                            + "matches most of the book and answers nothing.");
        }

        LocalDate today = LocalDate.now(clock);
        List<Subject> found = debtRecords.searchOwn(
                tenantId,
                Subject.normalizeName(trimmed),
                SubjectIdentifier.normalizeValue(trimmed),
                today);

        List<Result> results = new ArrayList<>();
        for (Subject subject : found.stream().limit(MAX_RESULTS).toList()) {
            results.add(summarise(subject, tenantId, today));
        }

        // Audited like any other lookup. This one stays inside the tenant, so it is not the
        // sensitive read the exchange inquiry is — but "who has been searching for whom, and
        // when" is a question somebody will eventually ask about an operator's own staff too.
        audit.record("TIX_SEARCH", "Subject", null, AuditService.OUTCOME_SUCCESS, actorId,
                results.size() + " result(s)");
        return List.copyOf(results);
    }

    /**
     * The operator's own book, listed rather than searched.
     *
     * <p>Search answers a question somebody already knew how to ask. This answers the one they
     * ask first — <em>who is in here?</em> — which until now had no answer at all: the only way
     * to reach a subject was to guess enough of its name to clear the three-character minimum.
     *
     * <p><strong>Name order, not risk order.</strong> A directory is for finding somebody, and
     * the worst-first view already exists on the exposure screen. Two screens ranking the same
     * book by different rules would be two answers to one question.
     *
     * <p>Audited like the search, and for the same reason: reading a list of everybody the
     * operator has reported is a bulk read of personal data, even when it is the operator's own.
     */
    @Transactional(readOnly = true)
    public Browse browseOwn(Subject.SubjectType type, UUID actorId) {
        UUID tenantId = TenantContext.require();
        LocalDate today = LocalDate.now(clock);

        // One more than the page, so the screen can say the list is cut short rather than end
        // silently at a round number and let somebody believe they have seen the whole book.
        List<Subject> found = debtRecords.listOwnByType(
                tenantId, type, today, Limit.of(MAX_RESULTS + 1));
        boolean more = found.size() > MAX_RESULTS;

        List<Result> results = new ArrayList<>();
        for (Subject subject : found.stream().limit(MAX_RESULTS).toList()) {
            results.add(summarise(subject, tenantId, today));
        }

        audit.record("TIX_BROWSE", "Subject", null, AuditService.OUTCOME_SUCCESS, actorId,
                type + ": " + results.size() + " subject(s)" + (more ? ", truncated" : ""));
        return new Browse(List.copyOf(results), more);
    }

    /**
     * @param truncated the operator holds more of this kind than the page shows. Said plainly,
     *                  because a list that stops at a round number without saying so reads as the
     *                  whole book
     */
    public record Browse(List<Result> subjects, boolean truncated) {
    }

    /**
     * Everything this operator holds about one subject.
     *
     * <p>Refuses a subject the operator has no record against, and refuses it as "not found"
     * rather than "not yours". The two must be indistinguishable: an endpoint that says "not
     * yours" confirms the subject exists, which turns a profile page into the enumeration tool
     * the search deliberately is not.
     */
    @Transactional(readOnly = true)
    public Profile profileOf(UUID subjectId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        LocalDate today = LocalDate.now(clock);

        List<DebtRecord> mine = debtRecords
                .findByTenantIdAndSubjectIdOrderByDefaultDateDesc(tenantId, subjectId).stream()
                .filter(record -> !record.isExpiredAsOf(today))
                .toList();
        if (mine.isEmpty()) {
            throw new SubjectNotHeldException(subjectId);
        }

        Subject subject = mine.get(0).getSubject();
        List<Held> held = new ArrayList<>();
        for (DebtRecord record : mine) {
            held.add(new Held(
                    record.getId(),
                    record.getStatus(),
                    record.getAmount().toPlainString(),
                    record.getCurrency(),
                    record.getServiceCategory(),
                    record.getDefaultDate(),
                    AgingBand.of(record.getDefaultDate(), today),
                    record.getRetentionUntil(),
                    record.getOrigin() == ai.dival.dip.modules.ingest.RecordOrigin.IMPORT));
        }

        audit.record("TIX_PROFILE_VIEWED", "Subject", subjectId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, held.size() + " record(s) held");

        return new Profile(
                subject.getId(),
                subject.getFullName(),
                subject.getSubjectType(),
                subject.getDateOfBirth(),
                subject.getNationality(),
                subject.getIdentifiers().stream()
                        .map(identifier -> new Identifier(
                                identifier.getIdentifierType(), identifier.getNormalizedValue()))
                        .toList(),
                summarise(subject, tenantId, today),
                List.copyOf(held),
                // The operator's own file, never anybody else's. How recently a competitor
                // touched its records is the "never since when" the exchange refuses, and it does
                // not become acceptable by being rendered as "2 days ago".
                mine.stream().map(DebtRecord::getUpdatedAt).filter(java.util.Objects::nonNull)
                        .max(java.util.Comparator.naturalOrder()).orElse(null));
    }

    private Result summarise(Subject subject, UUID tenantId, LocalDate today) {
        List<DebtRecord> records = debtRecords
                .findByTenantIdAndSubjectIdOrderByDefaultDateDesc(tenantId, subject.getId()).stream()
                .filter(record -> !record.isExpiredAsOf(today))
                .toList();

        BigDecimal outstanding = BigDecimal.ZERO;
        String currency = null;
        boolean mixedCurrency = false;
        int open = 0;
        LocalDate oldest = null;

        for (DebtRecord record : records) {
            if (currency == null) {
                currency = record.getCurrency();
            } else if (!currency.equals(record.getCurrency())) {
                // Two currencies cannot be summarised as one figure, and quietly adding them is
                // the mistake the exposure view exists to avoid. Say so instead.
                mixedCurrency = true;
            }
            if (record.getStatus() == DebtStatus.OUTSTANDING) {
                outstanding = outstanding.add(record.getAmount());
                open++;
            }
            if (oldest == null || record.getDefaultDate().isBefore(oldest)) {
                oldest = record.getDefaultDate();
            }
        }

        return new Result(
                subject.getId(),
                subject.getFullName(),
                subject.getSubjectType(),
                records.size(),
                open,
                mixedCurrency ? null : outstanding.toPlainString(),
                mixedCurrency ? null : currency,
                mixedCurrency,
                oldest,
                oldest == null ? null : AgingBand.of(oldest, today));
    }

    /**
     * One row of results.
     *
     * @param outstanding null when the subject's records span more than one currency, because a
     *                    single figure would then be of nothing. {@code mixedCurrency} says which
     *                    of the two reasons a null means.
     */
    public record Result(UUID subjectId, String name, Subject.SubjectType subjectType,
                         int recordCount, int openCount,
                         String outstanding, String currency, boolean mixedCurrency,
                         LocalDate oldestDefault, AgingBand oldestBand) {
    }

    /**
     * @param lastUpdatedAt when this operator's own file on the subject last changed, or null.
     *                      Scoped to {@code records} above, which are this tenant's; the freshness
     *                      of another operator's file is not disclosed and is not derivable here
     */
    public record Profile(UUID subjectId, String name, Subject.SubjectType subjectType,
                          LocalDate dateOfBirth, String nationality,
                          List<Identifier> identifiers, Result summary, List<Held> records,
                          Instant lastUpdatedAt) {
    }

    /**
     * An identifier as the exchange stores it.
     *
     * <p>Normalised rather than as submitted, which is the form matching runs on and therefore the
     * form worth showing: if a search for what is printed on a document fails, this is where the
     * difference will be visible.
     */
    public record Identifier(IdentifierType type, String value) {
    }

    /** @param imported whether this record came from a file rather than the API */
    public record Held(UUID recordId, DebtStatus status, String amount, String currency,
                       String serviceCategory, LocalDate defaultDate, AgingBand band,
                       LocalDate retainedUntil, boolean imported) {
    }

    /** Deliberately indistinguishable from a subject that does not exist at all. */
    public static class SubjectNotHeldException extends ResourceNotFoundException {
        public SubjectNotHeldException(UUID id) {
            super("Subject not found: " + id);
        }
    }
}
