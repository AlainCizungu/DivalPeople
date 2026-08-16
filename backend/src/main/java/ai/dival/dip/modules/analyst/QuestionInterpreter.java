package ai.dival.dip.modules.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a typed question into one of a closed set of intents, with a model if there is one.
 *
 * <p><strong>The model classifies; it never computes.</strong> It receives the user's sentence and
 * returns a small JSON object naming an intent and its parameters. Every figure the user eventually
 * reads is counted from rows by this platform. That division is what keeps an answer checkable, and
 * checkable is the property this product actually sells — a model that produced "37 businesses,
 * $2.4M" from its own head would be indistinguishable on screen from one that counted, right up
 * until somebody checked.
 *
 * <p><strong>The rules are not a stub.</strong> They run whenever no model is configured, whenever
 * the model is unreachable, and whenever what it returns is not one of the known intents. They
 * handle the phrasings this product was specified around, in English and French, and the analyst is
 * fully usable with no key at all. A fallback that only half-works is a fallback nobody notices is
 * running.
 */
@Component
public class QuestionInterpreter {

    /**
     * What the model is told it may answer with.
     *
     * <p>Deliberately narrow and deliberately explicit about refusing. A classifier prompt that
     * does not say "return UNSUPPORTED when unsure" gets a confident guess instead, which is the
     * failure this whole arrangement exists to avoid.
     */
    private static final String PROMPT = """
            You classify questions asked of a credit-risk registry. Reply with JSON only, no prose.

            {"intent": "...", "minAmount": number|null, "days": number|null, "subjectName": string|null}

            intent must be exactly one of:
              EXPOSURE_ABOVE                     - companies owing the asker more than an amount
              EXPOSURE_ABOVE_MULTI_INSTITUTION   - the same, but only those reported by more than
                                                   one institution
              WHY_RISKY                          - why one named company looks risky
              WHAT_CHANGED                       - what entered or left the asker's book recently
              PRIORITISE                         - which unpaid accounts to chase first
              UNSUPPORTED                        - anything else

            Rules:
            - Never invent figures. You are choosing a question, not answering one.
            - minAmount is the number in the question, if any. Ignore currency words.
            - days is the window in days: "this week" 7, "this month" 30, "this quarter" 90.
            - subjectName is the company named, if any, copied exactly.
            - If you are not confident, return UNSUPPORTED. That is a correct answer.
            """;

    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d[\\d ,.]*)\\s*(k|m)?", Pattern.CASE_INSENSITIVE);

    private final AiGateway model;
    private final ObjectMapper json;

    public QuestionInterpreter(AiGateway model, ObjectMapper json) {
        this.model = model;
        this.json = json;
    }

    public Interpretation interpret(String question, UUID actorId) {
        if (question == null || question.isBlank()) {
            return Interpretation.unsupported(false);
        }
        if (model.canInterpret()) {
            Optional<Interpretation> read = model.classify(PROMPT, question, actorId)
                    .flatMap(this::parse);
            if (read.isPresent()) {
                return read.get();
            }
            // Fell through: unreachable, or a reply this code could not make sense of. The rules
            // answer instead, and the interpretation says it was the rules — so a screen full of
            // rule-based readings is a visible symptom rather than a silent degradation.
        }
        return byRule(question).asRuleBased();
    }

    /**
     * Reads the model's JSON, and refuses anything outside the closed list.
     *
     * <p>An intent this code does not know is treated as no answer at all rather than as
     * UNSUPPORTED, so that a model returning something plausible-but-invented degrades to the rules
     * instead of to a shrug.
     */
    private Optional<Interpretation> parse(String reply) {
        try {
            String cleaned = reply.trim()
                    .replaceAll("^```(?:json)?", "")
                    .replaceAll("```$", "")
                    .trim();
            JsonNode node = json.readTree(cleaned);
            Intent intent = Intent.valueOf(node.path("intent").asText());
            BigDecimal minAmount = node.path("minAmount").isNumber()
                    ? node.path("minAmount").decimalValue() : null;
            int days = node.path("days").isNumber() ? node.path("days").asInt() : 0;
            String name = node.path("subjectName").isTextual()
                    ? node.path("subjectName").asText().trim() : null;
            return Optional.of(new Interpretation(intent, minAmount, days,
                    name == null || name.isBlank() ? null : name, true));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException bad) {
            return Optional.empty();
        }
    }

    /**
     * The same job, done with patterns, in both languages.
     *
     * <p>Order matters: the multi-institution reading has to be tested before the plain exposure
     * one, because every sentence that means the first also contains the words that mean the
     * second.
     */
    private Interpretation byRule(String question) {
        String text = question.toLowerCase(Locale.ROOT);

        if (mentions(text, "why", "pourquoi") && (mentions(text, "risk", "risque"))) {
            return new Interpretation(Intent.WHY_RISKY, null, 0, nameIn(question), false);
        }
        if (mentions(text, "changed", "change", "changé", "nouveau", "new")) {
            return new Interpretation(Intent.WHAT_CHANGED, null, windowIn(text), null, false);
        }
        if (mentions(text, "priorit", "chase", "collection", "recouvr")) {
            return new Interpretation(Intent.PRIORITISE, null, 0, null, false);
        }
        if (mentions(text, "exposure", "owe", "owing", "encours", "doit", "impay")) {
            boolean multi = mentions(text, "multiple institution", "more than one institution",
                    "several institution", "plusieurs institution", "multi");
            return new Interpretation(
                    multi ? Intent.EXPOSURE_ABOVE_MULTI_INSTITUTION : Intent.EXPOSURE_ABOVE,
                    amountIn(text), 0, null, false);
        }
        return Interpretation.unsupported(false);
    }

    private static boolean mentions(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first number in the sentence, with k and m expanded.
     *
     * <p>Separators are stripped rather than parsed: "20,000" and "20 000" are the same amount
     * written on two sides of a border, and a Congolese user may type either.
     */
    private static BigDecimal amountIn(String text) {
        Matcher matcher = AMOUNT.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String digits = matcher.group(1).replaceAll("[ ,.]", "");
        if (digits.isBlank()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(digits);
        String scale = matcher.group(2);
        if (scale == null) {
            return amount;
        }
        return scale.equalsIgnoreCase("k") ? amount.multiply(BigDecimal.valueOf(1_000))
                : amount.multiply(BigDecimal.valueOf(1_000_000));
    }

    /** A week, a month or a quarter. Defaults to a week, which is what "recently" usually means. */
    private static int windowIn(String text) {
        if (mentions(text, "quarter", "trimestre")) {
            return 90;
        }
        if (mentions(text, "month", "mois")) {
            return 30;
        }
        return 7;
    }

    /**
     * The company named in the question, taken as the longest run of capitalised words.
     *
     * <p>Crude, and it does not need to be better: the answer resolves whatever it finds against
     * the operator's own book and says what it matched. A wrong guess produces "no company of that
     * name in your book", which is a visible miss rather than a wrong answer about the right
     * company.
     */
    private static String nameIn(String question) {
        Matcher matcher = Pattern.compile("([A-Z][\\w'’-]*(?:\\s+[A-Z][\\w'’-]*)*)")
                .matcher(question);
        String longest = null;
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (longest == null || candidate.length() > longest.length()) {
                longest = candidate;
            }
        }
        return longest;
    }
}
