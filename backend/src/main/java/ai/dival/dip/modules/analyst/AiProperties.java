package ai.dival.dip.modules.analyst;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the language model is, and whether it is allowed to see anything.
 *
 * <p><strong>Two switches, not one, and the split is the whole point.</strong> Reading the question
 * and writing the answer send very different things out of the country.
 *
 * <p>{@code enabled} lets the model read <em>what the user typed</em> — "businesses over $20,000
 * across multiple institutions" — and return a structured intent. That text contains no debtor, no
 * amount from the registry and no company name unless the user typed one. Nothing about anybody in
 * the registry leaves.
 *
 * <p>{@code narrate} lets the model see <em>the answer DIP computed</em> so it can phrase it:
 * company names, exposures, ages. That is a transfer of personal data to a processor outside the
 * DRC, and as of August 2026 <strong>counsel has not been asked whether there is a basis for
 * it</strong>. It is configurable, it is recorded in the audit trail on every call, and the screen
 * says so while it is on. That is the most this code can do about a question that is not an
 * engineering question.
 *
 * <p>No key lives in this repository or in any file it tracks. Absent one, both switches are inert
 * and the analyst falls back to reading questions by rule and phrasing answers from a template —
 * which is less impressive and equally true.
 *
 * @param enabled   whether the model may read the user's question
 * @param narrate   whether the model may read DIP's computed answer and phrase it. Sends registry
 *                  data abroad
 * @param baseUrl   an OpenAI-compatible chat completions host
 * @param apiKey    from the environment, never from a file in this repository
 * @param model     the model name, stamped onto every answer it touches
 * @param timeoutMs how long to wait before falling back to the template rather than hanging a
 *                  screen on somebody else's outage
 */
@ConfigurationProperties(prefix = "dip.ai")
public record AiProperties(boolean enabled, boolean narrate, String baseUrl, String apiKey,
                           String model, int timeoutMs) {

    public AiProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
        timeoutMs = timeoutMs <= 0 ? 12_000 : timeoutMs;
        apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** A switch on with no key is a misconfiguration, and it must behave as off rather than fail. */
    public boolean canReachModel() {
        return enabled && !apiKey.isBlank();
    }

    /** Narration additionally requires the model to be reachable at all. */
    public boolean canNarrate() {
        return canReachModel() && narrate;
    }
}
