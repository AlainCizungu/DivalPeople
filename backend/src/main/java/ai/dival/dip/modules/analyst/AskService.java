package ai.dival.dip.modules.analyst;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.modules.tix.DebtRecordService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ask DIP: a typed question, an answer counted from rows.
 *
 * <p><strong>The model reads the question. This class computes the answer.</strong> Every figure a
 * user sees is a sum or a count over records the caller is already entitled to, produced by SQL and
 * reproducible on demand. The model is never asked what a number is, because a model that produced
 * "37 businesses, $2.4M" from its own head would look exactly like one that counted — right up
 * until somebody checked, which for a credit registry is the wrong moment to find out.
 *
 * <p><strong>"Your exposure" is said in those words, every time.</strong> The obvious caption for
 * this screen is "combined exposure", and it would be read as the market's. This platform cannot
 * total what other institutions are owed and would not disclose it if it could; what it can total
 * is the asker's own book, and pairing that with a count of how many institutions report each
 * company is the honest shape of the answer.
 *
 * <p><strong>Screening across institutions costs inquiries, and the price is quoted first.</strong>
 * How many institutions report a company is precisely what an inquiry discloses, so asking it about
 * forty companies is forty inquiries against the same hourly allowance as everybody else. The
 * answer says what it would cost and does not spend it until the caller says so — the same decision
 * as the watchlist sweep, arrived at for the same reason.
 */
@Service
public class AskService {

    /**
     * The one currency the analyst totals.
     *
     * <p>Adding two would need a rate this application has no business inventing, which is the same
     * refusal the risk model makes. Counsel confirmed both operator files are USD in August 2026,
     * so in practice nothing is left out; if that changes, the answer will be short rather than
     * wrong.
     */
    private static final String CURRENCY = "USD";

    /** More than this on one screen is a report, not an answer. The count is still exact. */
    private static final int MAX_LISTED = 50;

    private final QuestionInterpreter interpreter;
    private final DebtRecordService debtRecords;
    private final AiGateway model;
    private final AuditService audit;
    private final Clock clock;

    public AskService(QuestionInterpreter interpreter, DebtRecordService debtRecords,
                      AiGateway model, AuditService audit, Clock clock) {
        this.interpreter = interpreter;
        this.debtRecords = debtRecords;
        this.model = model;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Answer ask(String question, UUID actorId) {
        Interpretation read = interpreter.interpret(question, actorId);

        // The question itself, not the answer. Somebody reviewing how this screen is used needs to
        // know what people asked, and the answers are reproducible from the questions anyway.
        audit.record("AI_QUESTION_ASKED", "Analyst", null, AuditService.OUTCOME_SUCCESS, actorId,
                read.intent() + ": " + question);

        Answer answer = switch (read.intent()) {
            case EXPOSURE_ABOVE, EXPOSURE_ABOVE_MULTI_INSTITUTION -> exposure(read);
            case WHAT_CHANGED -> changed(read);
            case PRIORITISE -> prioritise(read);
            case WHY_RISKY -> new Answer(read, List.of(), List.of(), 0, null);
            case UNSUPPORTED -> new Answer(read, List.of(), List.of(), 0, null);
        };

        return narrate(answer, actorId);
    }

    /**
     * Companies owing more than a threshold, largest first.
     *
     * <p>Every row is this operator's own. The multi-institution variant returns the same list plus
     * the price of screening it, and screens nothing: spending forty inquiries because a sentence
     * was ambiguous is not a thing to do without being asked.
     */
    private Answer exposure(Interpretation read) {
        LocalDate today = LocalDate.now(clock);
        BigDecimal threshold = read.minAmount() == null ? BigDecimal.ZERO : read.minAmount();
        List<Object[]> rows = debtRecords.exposureBySubject(CURRENCY, today, threshold);

        List<Company> companies = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        long overAYear = 0;
        for (Object[] row : rows) {
            BigDecimal owed = (BigDecimal) row[2];
            LocalDate oldest = ((Date) row[3]).toLocalDate();
            long days = ChronoUnit.DAYS.between(oldest, today);
            total = total.add(owed);
            if (days > 365) {
                overAYear++;
            }
            if (companies.size() < MAX_LISTED) {
                companies.add(new Company((UUID) row[0], (String) row[1], owed.toPlainString(),
                        days, ((Number) row[4]).intValue()));
            }
        }

        List<Figure> figures = List.of(
                new Figure("COMPANIES", String.valueOf(rows.size()), null),
                new Figure("YOUR_EXPOSURE", total.toPlainString(), CURRENCY),
                new Figure("OVER_A_YEAR", String.valueOf(overAYear), null));

        // Quoted, not spent. One inquiry per company screened, against the same allowance as
        // everything else — so the caller decides whether the question is worth the day's budget.
        int inquiriesItWouldCost =
                read.intent() == Intent.EXPOSURE_ABOVE_MULTI_INSTITUTION ? rows.size() : 0;

        return new Answer(read, figures, companies, inquiriesItWouldCost, null);
    }

    /** What entered the book inside the window. Own records, so it costs nothing. */
    private Answer changed(Interpretation read) {
        int days = read.days() > 0 ? read.days() : 7;
        Instant since = clock.instant().minus(days, ChronoUnit.DAYS);

        return new Answer(read,
                List.of(new Figure("DECLARED_IN_WINDOW",
                                String.valueOf(debtRecords.declaredSince(since)), null),
                        new Figure("WINDOW_DAYS", String.valueOf(days), null)),
                List.of(), 0, null);
    }

    /**
     * Unpaid accounts, ranked by amount and age.
     *
     * <p><strong>A sort, and the answer says so.</strong> Nothing here predicts recovery: this
     * platform holds who was owed money and not who eventually paid, so a ranking presented as a
     * prediction would be the most quietly dishonest thing this screen could do. The ordering is
     * published — largest first, and the age of the oldest unpaid record beside it — so a
     * collections manager can disagree with it on the evidence.
     */
    private Answer prioritise(Interpretation read) {
        Answer all = exposure(new Interpretation(Intent.EXPOSURE_ABOVE, BigDecimal.ZERO, 0,
                null, read.byModel()));
        return new Answer(read, all.figures(), all.companies(), 0, null);
    }

    /**
     * Hands the computed answer to the model to phrase, if that is switched on.
     *
     * <p><strong>This is where registry data leaves the country.</strong> Company names and the
     * amounts this operator is owed go to a processor abroad. It is configurable, it writes an
     * audit row on every call, the screen says so while it is on, and counsel has not been asked
     * whether there is a basis for it — that question is in {@code docs/OLIVIER_ANSWERS.md} and it
     * is not an engineering question.
     *
     * <p>The prose is decoration over figures that are already correct. If the model is
     * unreachable, slow or wrong, the answer is unchanged and the screen renders the figures as it
     * always does.
     */
    private Answer narrate(Answer answer, UUID actorId) {
        if (!model.canNarrate() || answer.figures().isEmpty()) {
            return answer;
        }
        String facts = answer.figures().stream()
                .map(figure -> figure.code() + "=" + figure.value()
                        + (figure.unit() == null ? "" : " " + figure.unit()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("");

        String prompt = """
                You write one or two sentences summarising figures a credit registry has already
                computed. Use only the figures given. Never add a number, a name or a judgement
                that is not in them. If the figures are empty, say nothing was found.
                """;

        return model.narrate(prompt, facts, actorId)
                .map(prose -> answer.withNarrative(prose.trim(), model.modelName()))
                .orElse(answer);
    }

    /**
     * @param understood     what the question was taken to mean, printed on screen so a right
     *                       answer to the wrong question is visible in a second
     * @param figures        every number, counted from rows by this platform and never by a model
     * @param companies      the ones worth naming, capped for the screen. {@code figures} stays exact
     * @param inquiryCost    what screening across institutions would cost, quoted and not spent
     * @param narrative      the model's phrasing of the figures, or null. Decoration over numbers
     *                       that are already correct
     * @param narratedBy     which model wrote the narrative, so an odd sentence can be attributed
     */
    public record Answer(Interpretation understood, List<Figure> figures, List<Company> companies,
                         int inquiryCost, String narrative, String narratedBy) {

        public Answer(Interpretation understood, List<Figure> figures, List<Company> companies,
                      int inquiryCost, String narrative) {
            this(understood, figures, companies, inquiryCost, narrative, null);
        }

        Answer withNarrative(String prose, String modelName) {
            return new Answer(understood, figures, companies, inquiryCost, prose, modelName);
        }
    }

    /** @param code a key the screen turns into words; the server sends no rendered text */
    public record Figure(String code, String value, String unit) {
    }

    /**
     * @param owed       what this operator is owed, not what the market is
     * @param oldestDays age of its oldest unpaid record, which is what separates a late invoice
     *                   from a write-off
     */
    public record Company(UUID subjectId, String name, String owed, long oldestDays, int records) {
    }
}
