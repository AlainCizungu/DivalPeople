package ai.dival.dip.modules.analyst;

import ai.dival.dip.common.audit.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The only place in this application that talks to a language model.
 *
 * <p>One class, so that the two questions a regulator will ask — what left, and when — have one
 * answer each rather than being scattered across whatever called out that day.
 *
 * <p><strong>Every outbound call writes an audit row before it is made.</strong> Not after: a call
 * that fails still sent the data. The row records which kind of payload left — a question the user
 * typed, or an answer computed from the registry — and the host it went to, because those are the
 * two facts that decide whether a transfer was lawful.
 *
 * <p><strong>It never throws upward.</strong> A model that is slow, rate-limited, unreachable or
 * mid-outage must degrade the analyst to reading questions by rule and phrasing answers from a
 * template. An outage at a supplier is not a reason a credit officer cannot see their own book.
 *
 * <p>The prompt says, and the parsing enforces, that the model chooses from a closed list. It never
 * computes a figure. Every number a user sees is counted from rows by this platform, which is what
 * makes an answer checkable — and checkable is the only property this product actually sells.
 */
@Component
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    /** Recorded when the user's own words go out. Carries nothing from the registry. */
    public static final String SENT_QUESTION = "AI_QUESTION_SENT";

    /**
     * Recorded when a computed answer goes out to be phrased.
     *
     * <p>This is the one that is a cross-border transfer of registry data. Named separately from
     * the question so that a search of the audit trail can answer "did anything about our debtors
     * leave the country" without reading payloads.
     */
    public static final String SENT_ANSWER = "AI_ANSWER_SENT";

    private final AiProperties properties;
    private final AuditService audit;
    private final ObjectMapper json;
    private final RestClient http;

    public AiGateway(AiProperties properties, AuditService audit, ObjectMapper json) {
        this.properties = properties;
        this.audit = audit;
        this.json = json;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.timeoutMs()));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean canInterpret() {
        return properties.canReachModel();
    }

    public boolean canNarrate() {
        return properties.canNarrate();
    }

    public String modelName() {
        return properties.model();
    }

    /**
     * Asks the model to classify a question, and returns its raw reply.
     *
     * <p>Only the user's text goes out. Empty when the model is unreachable for any reason, which
     * the caller reads as "fall back to the rules" rather than as an error.
     */
    public Optional<String> classify(String systemPrompt, String question, UUID actorId) {
        return call(systemPrompt, question, SENT_QUESTION, "question", actorId);
    }

    /**
     * Asks the model to phrase an answer DIP has already computed.
     *
     * <p><strong>This sends registry data abroad.</strong> The facts handed over are the ones the
     * caller is already entitled to see — that is enforced by what the caller assembles, not here —
     * but they concern real companies and they leave the country.
     */
    public Optional<String> narrate(String systemPrompt, String facts, UUID actorId) {
        return call(systemPrompt, facts, SENT_ANSWER, "computed answer", actorId);
    }

    private Optional<String> call(String systemPrompt, String content, String action,
                                  String what, UUID actorId) {
        if (!properties.canReachModel()) {
            return Optional.empty();
        }

        // Before the call, not after. A request that times out still left the building, and an
        // audit trail that only records successes is an audit trail that hides the interesting
        // cases.
        audit.record(action, "AiModel", properties.model(), AuditService.OUTCOME_SUCCESS, actorId,
                what + " sent to " + host());

        try {
            String body = http.post()
                    .uri(properties.baseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", properties.model(),
                            // Zero, because this is a classifier and a formatter rather than a
                            // writer. The same question must produce the same intent twice, or the
                            // audit trail records an answer nobody can reproduce.
                            "temperature", 0,
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", content))))
                    .retrieve()
                    .body(String.class);

            JsonNode reply = json.readTree(body);
            JsonNode message = reply.path("choices").path(0).path("message").path("content");
            return message.isTextual() ? Optional.of(message.asText()) : Optional.empty();
        } catch (Exception unreachable) {
            // Deliberately broad, and deliberately not rethrown. Every failure mode here — a
            // timeout, a 429, a revoked key, a provider outage, a body in a shape this code did
            // not expect — has the same correct response: carry on without it.
            log.warn("The model could not be reached; falling back. {}", unreachable.toString());
            return Optional.empty();
        }
    }

    /** The host alone, for the audit line. A full URL would put a key-bearing path in the trail. */
    private String host() {
        try {
            return java.net.URI.create(properties.baseUrl()).getHost();
        } catch (RuntimeException malformed) {
            return "unknown host";
        }
    }
}
