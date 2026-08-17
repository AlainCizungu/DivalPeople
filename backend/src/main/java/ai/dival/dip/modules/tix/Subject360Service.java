package ai.dival.dip.modules.tix;

import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.modules.risk.RiskIndicator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One company, everything the platform can say about it, on one screen.
 *
 * <p>A search result used to end at a list of rows. This is the screen that ends somewhere useful:
 * the indicator and what drives it, the operator's own file, what the exchange answers, the signals
 * worth acting on, and a timeline. It is the product's main view and it composes existing services
 * rather than reaching past them.
 *
 * <p><strong>It holds no repository, and that is the safety argument.</strong> Everything here
 * comes from {@link SearchService#profileOf} and {@link ExchangeService#inquire} — two calls the
 * user could have made themselves, under their own tenant and their own roles, with row-level
 * security and the dispute filter and the retention filter all still in front of them. Nothing on
 * this screen is reachable that was not already reachable by clicking twice. Same argument as the
 * evidence pack, and it is the reason a screen this rich is not a new disclosure surface.
 *
 * <p><strong>Every figure in the overview is the caller's own, and is labelled as theirs.</strong>
 * The obvious caption is "total known exposure" and it would be read as the market's. What this
 * platform can total is the asker's book; what it can add beside it is how many institutions report
 * the same subject. The one exception is {@code marketExposure}, which is null in every deployment
 * that has not switched pricing on — see {@link DisclosureProperties}.
 *
 * <p><strong>Costs one inquiry.</strong> The screen asks the exchange, so it charges the rate
 * limiter and writes the audit row with a stated purpose, exactly as the inquiry screen does. A
 * profile view that reached the exchange for free would be a way to query it with the throttle off,
 * and it would look identical.
 */
@Service
public class Subject360Service {

    /**
     * Which rules assembled this view.
     *
     * <p>Stamped for the reason the risk model and the evidence pack are: a decision defended in
     * three years has to be explainable by what the platform did then.
     */
    public static final String VIEW_VERSION = "DIP-360-1";

    /** Past this, an unpaid obligation stops being a late invoice and starts being a write-off. */
    private static final long LONG_OVERDUE_DAYS = 365;

    /** A timeline, not a ledger. The records themselves are on the same screen, in full. */
    private static final int MAX_EVENTS = 24;

    private final SearchService search;
    private final ExchangeService exchange;
    private final Clock clock;

    public Subject360Service(SearchService search, ExchangeService exchange, Clock clock) {
        this.search = search;
        this.exchange = exchange;
        this.clock = clock;
    }

    /**
     * Assembles the view for a subject this operator holds a record against.
     *
     * <p>Refuses a subject the operator holds nothing about, and refuses it as <em>not found</em>
     * rather than <em>not yours</em>. The two must stay indistinguishable: "not yours" confirms the
     * subject exists, and a screen that confirms existence for any id is an enumeration tool with a
     * nice layout. {@link SearchService#profileOf} already makes that refusal, and it is called
     * first so that the refusal costs no inquiry.
     *
     * @param purpose why this company is being looked at. Required, because this asks the exchange
     */
    @Transactional
    public Subject360 assemble(UUID subjectId, String purpose, UUID actorId) {
        if (purpose == null || purpose.isBlank()) {
            throw new PolicyRefusedException(
                    "Say why this company is being looked at. Opening a profile asks the exchange, "
                            + "which is charged and recorded like any other inquiry.");
        }

        SearchService.Profile held = search.profileOf(subjectId, actorId);
        InquiryResult answer = exchange.inquire(
                new InquiryRequest(nationalDocuments(held), held.name(), purpose.trim()), actorId);

        LocalDate today = LocalDate.now(clock);
        Overview overview = overview(held, answer, today);

        return new Subject360(
                VIEW_VERSION,
                clock.instant(),
                held.subjectId(),
                held.name(),
                held.subjectType(),
                held.identifiers(),
                answer.indicator(),
                answer.outcome(),
                overview,
                signals(held, answer, overview),
                answer.contributors(),
                // The screen has to be able to say "this platform does not name them" rather than
                // render an empty table, and it cannot infer that from an empty list — an empty
                // list is also what a subject nobody else reports looks like.
                answer.contributors().isEmpty(),
                timeline(held));
    }

    /**
     * The documents that identify this company to anybody, not just to us.
     *
     * <p>An account reference is excluded even though it is a strong identifier by every other
     * measure. It resolves inside the institution that issued it, so asking the exchange with one
     * finds this operator's own record and nothing else — a profile that confirmed itself.
     */
    private static List<InquiryRequest.SubmittedIdentifier> nationalDocuments(
            SearchService.Profile held) {
        List<InquiryRequest.SubmittedIdentifier> documents = new ArrayList<>();
        for (SearchService.Identifier identifier : held.identifiers()) {
            if (identifier.type().isStrong() && !identifier.type().isOperatorScoped()) {
                documents.add(
                        new InquiryRequest.SubmittedIdentifier(identifier.type(), identifier.value()));
            }
        }
        return List.copyOf(documents);
    }

    /**
     * The numbers at the top of the screen.
     *
     * <p>Counted over the records {@link SearchService#profileOf} returned, which have already had
     * expiry applied. So a record past its retention period is absent from the total, the count and
     * the timeline alike, rather than being filtered in one of the three.
     */
    private Overview overview(SearchService.Profile held, InquiryResult answer, LocalDate today) {
        BigDecimal yours = BigDecimal.ZERO;
        String currency = null;
        boolean mixed = false;
        int open = 0;
        int pastDue = 0;
        int contested = 0;
        long oldestUnpaidDays = -1;

        for (SearchService.Held record : held.records()) {
            if (record.status() == DebtStatus.DISPUTED
                    || record.status() == DebtStatus.UNDER_INVESTIGATION) {
                contested++;
            }
            if (record.status() != DebtStatus.OUTSTANDING) {
                continue;
            }
            open++;
            long age = ChronoUnit.DAYS.between(record.defaultDate(), today);
            if (age > 0) {
                pastDue++;
            }
            oldestUnpaidDays = Math.max(oldestUnpaidDays, age);

            // Same refusal the risk model and the contributor list make: a sum of dollars and
            // francs is not a smaller number, it is not a number. Latches once seen.
            if (currency == null) {
                currency = record.currency();
            }
            if (!mixed && currency.equalsIgnoreCase(record.currency())) {
                yours = yours.add(new BigDecimal(record.amount()));
            } else {
                mixed = true;
            }
        }

        return new Overview(
                mixed ? null : yours.toPlainString(),
                mixed ? null : currency,
                held.records().size(),
                open,
                pastDue,
                contested,
                oldestUnpaidDays,
                answer.institutionCount(),
                // Own book only. How recently *another* operator touched its file is exactly the
                // "never since when" the exchange refuses, and it is not made acceptable by being
                // rendered as a relative time.
                daysSinceOwnUpdate(held),
                marketExposure(answer));
    }

    /**
     * The market total, when this deployment discloses amounts at all.
     *
     * <p>Summed from the contributor list rather than computed separately, so that it cannot
     * disagree with the rows beneath it. Null when pricing is off, and null when any contributor's
     * amount is withheld — a partial total presented as a total is the error that costs a lender
     * money, and it is invisible on screen.
     */
    private static String marketExposure(InquiryResult answer) {
        if (answer.contributors().isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (InquiryResult.Contributor contributor : answer.contributors()) {
            if (contributor.owed() == null) {
                return null;
            }
            total = total.add(new BigDecimal(contributor.owed()));
        }
        return total.toPlainString();
    }

    /** How long since this operator's own file on the subject last changed. */
    private long daysSinceOwnUpdate(SearchService.Profile held) {
        Instant latest = held.lastUpdatedAt();
        return latest == null ? -1 : ChronoUnit.DAYS.between(latest, clock.instant());
    }

    /**
     * What a reader should act on, coded rather than phrased.
     *
     * <p>Coded so the screen can say them in either language, and so a signal is a fact with a
     * definition rather than a sentence somebody wrote. Each one is derived from a figure already
     * on this screen, which means a reader who disagrees can see the arithmetic.
     *
     * <p><strong>The clear signals are as important as the warnings</strong>, and one of them is
     * neither. {@code FRAUD_NOT_ASSESSED} is here because the alternative — printing a green tick
     * beside "no active fraud alert" — asserts a check this platform does not perform and cannot:
     * the registry's uniqueness rules make one document under two subjects impossible, so a
     * detector for it would report nothing forever, and a permanent green tick is indistinguishable
     * from a working detector that has found nothing.
     */
    private static List<Signal> signals(SearchService.Profile held, InquiryResult answer,
                                        Overview overview) {
        List<Signal> signals = new ArrayList<>();

        if (overview.openAccounts() > 1) {
            signals.add(Signal.MULTIPLE_OUTSTANDING_OBLIGATIONS);
        }
        if (overview.oldestUnpaidDays() > LONG_OVERDUE_DAYS) {
            signals.add(Signal.OBLIGATION_OLDER_THAN_A_YEAR);
        }
        if (overview.institutionCount() > 1) {
            signals.add(Signal.REPORTED_BY_SEVERAL_INSTITUTIONS);
        }
        if (!answer.fraudSignals().isEmpty()) {
            // An identifier that appears under more than one subject. Advisory, and it is a
            // question for a person rather than a finding about the company.
            signals.add(Signal.AN_IDENTIFIER_IS_REUSED);
        } else {
            signals.add(Signal.NO_IDENTIFIER_CONFLICT);
        }
        if (overview.contestedRecords() > 0) {
            // Said on the operator's own screen because the operator can see its own disputes.
            // The exchange withholds them from everybody else, including from the count above.
            signals.add(Signal.SOME_RECORDS_ARE_CONTESTED);
        }
        if (overview.openAccounts() == 0) {
            signals.add(Signal.NOTHING_OUTSTANDING_IN_YOUR_BOOK);
        }
        if (held.identifiers().stream().noneMatch(id -> id.type().isStrong()
                && !id.type().isOperatorScoped())) {
            signals.add(Signal.NO_NATIONAL_DOCUMENT_ON_FILE);
        }
        signals.add(Signal.FRAUD_NOT_ASSESSED);

        return List.copyOf(signals);
    }

    /**
     * The operator's own history with this company, newest first.
     *
     * <p><strong>Own records only, and the screen says so.</strong> A timeline is the most tempting
     * place to leak "since when", because it reads as context rather than as disclosure — a row
     * saying "2025 · second institution began reporting" looks like narrative and is a date attached
     * to a competitor's file. The exchange does not answer that question and this does not
     * reconstruct it.
     */
    private static List<Event> timeline(SearchService.Profile held) {
        List<Event> events = new ArrayList<>();
        for (SearchService.Held record : held.records()) {
            events.add(new Event(record.defaultDate(), EventCode.OBLIGATION_FELL_DUE,
                    record.serviceCategory()));
            if (record.status() == DebtStatus.SETTLED) {
                // Dated by the record's own default date rather than by when it was marked paid:
                // profileOf does not carry the settlement instant, and inventing a date from the
                // row's last update would be a guess rendered as a fact.
                events.add(new Event(record.defaultDate(), EventCode.OBLIGATION_SETTLED,
                        record.serviceCategory()));
            }
            if (record.status() == DebtStatus.DISPUTED
                    || record.status() == DebtStatus.UNDER_INVESTIGATION) {
                events.add(new Event(record.defaultDate(), EventCode.RECORD_CONTESTED,
                        record.serviceCategory()));
            }
        }
        events.sort(Comparator.comparing(Event::on).reversed());
        return List.copyOf(events.size() > MAX_EVENTS ? events.subList(0, MAX_EVENTS) : events);
    }

    /**
     * @param viewVersion           which rules assembled this
     * @param assembledAt           when, so a printed profile carries its own age
     * @param indicator             the DIP Risk Indicator with every factor behind it; null when
     *                              the exchange would not confirm the identity
     * @param outcome               what the exchange answered
     * @param contributors          the named operators, when this deployment names them. Empty
     *                              otherwise, which is the shipped state
     * @param contributorsWithheld  true when the list is empty because naming is off, so the screen
     *                              can say that rather than render nothing
     */
    public record Subject360(String viewVersion, Instant assembledAt, UUID subjectId, String name,
                             Subject.SubjectType subjectType,
                             List<SearchService.Identifier> identifiers,
                             RiskIndicator indicator, InquiryResult.Outcome outcome,
                             Overview overview, List<Signal> signals,
                             List<InquiryResult.Contributor> contributors,
                             boolean contributorsWithheld, List<Event> timeline) {
    }

    /**
     * @param yourExposure       what <em>this</em> operator is owed, never the market's. Null when
     *                           the operator's own records are in more than one currency
     * @param oldestUnpaidDays   age of the oldest unpaid obligation in this operator's book, or -1
     * @param institutionCount   how many operators report this subject, including this one
     * @param daysSinceUpdate    since this operator's own file last changed, or -1
     * @param marketExposure     the sum across named contributors. Null unless the deployment
     *                           discloses amounts, and null when any contributor's is withheld
     */
    public record Overview(String yourExposure, String currency, int yourRecords, int openAccounts,
                           int pastDueAccounts, int contestedRecords, long oldestUnpaidDays,
                           int institutionCount, long daysSinceUpdate, String marketExposure) {
    }

    /** @param on the date the event is placed at; the screen groups by year */
    public record Event(LocalDate on, EventCode code, String detail) {
    }

    /** Coded, so the screen can say it in either language and a reader can look up its rule. */
    public enum EventCode {
        OBLIGATION_FELL_DUE,
        OBLIGATION_SETTLED,
        RECORD_CONTESTED
    }

    /** Coded for the same reason, and each is derived from a figure elsewhere on the screen. */
    public enum Signal {
        MULTIPLE_OUTSTANDING_OBLIGATIONS,
        OBLIGATION_OLDER_THAN_A_YEAR,
        REPORTED_BY_SEVERAL_INSTITUTIONS,
        AN_IDENTIFIER_IS_REUSED,
        SOME_RECORDS_ARE_CONTESTED,
        NO_NATIONAL_DOCUMENT_ON_FILE,
        NO_IDENTIFIER_CONFLICT,
        NOTHING_OUTSTANDING_IN_YOUR_BOOK,
        FRAUD_NOT_ASSESSED
    }
}
