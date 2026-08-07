package ai.dival.dip.modules.tix;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Scores how confidently submitted details resolve to a stored subject.
 *
 * <p>Phase one is deliberately deterministic and explainable: a strong identifier carries the
 * match, and corroborating attributes raise confidence. The machine-learning matcher described in
 * {@code docs/TIX_MODULE.md} replaces the scoring here without changing this interface, so the
 * threshold logic in {@link ExchangeService} stays put.
 *
 * <p>Matching never merges subjects on its own. Below the threshold the caller is told to review.
 *
 * <p><strong>The score never leaves the server.</strong> It decides an outcome and is written to
 * the audit trail; it is not in the response. It is a fine-grained function of how the submitted
 * name compares to the stored one, so returning it answered "is this a token of their name" one
 * request at a time, and a dictionary of common Congolese names recovered a competitor's subject's
 * legal name token by token. See SECURITY_REVIEW.md H4.
 *
 * <p>A coarser oracle survives — the outcome itself still shifts when a submitted name disagrees —
 * and that is inherent to any matcher that answers at all. Rate limiting and an audit trail that
 * records the stated purpose are the controls for the residue, not this class.
 */
@Component
public class IdentityMatcher {

    private static final double STRONG_IDENTIFIER_BASE = 0.90;
    private static final double WEAK_IDENTIFIER_BASE = 0.60;
    private static final double NAME_AGREEMENT_BONUS = 0.08;
    private static final double NAME_CONFLICT_PENALTY = 0.25;

    /**
     * @param matched the identifier that actually resolved the subject, which is not necessarily
     *                one the caller would like it to be — see {@link #baseScore}
     * @return confidence in the range 0.0–1.0, for the server's own use
     */
    public double confidence(Subject subject, InquiryRequest request,
                             InquiryRequest.SubmittedIdentifier matched) {
        double score = baseScore(matched);

        if (request.fullName() != null && !request.fullName().isBlank()) {
            String submitted = Subject.normalizeName(request.fullName());
            String stored = subject.getNormalizedName();
            if (submitted.equals(stored)) {
                score += NAME_AGREEMENT_BONUS;
            } else if (!sharesAnyToken(submitted, stored)) {
                // Nothing in common with the stored name is a reason for caution, not rejection:
                // legal names, married names, and transliterations legitimately differ.
                score -= NAME_CONFLICT_PENALTY;
            }
        }

        return clamp(score);
    }

    /**
     * Scores the identifier that <em>matched</em>, not the strongest one submitted.
     *
     * <p>This previously asked whether <em>any</em> submitted identifier had a strong type, which
     * the caller controls completely. A real phone number alongside an invented passport number
     * therefore scored as a strong match: resolution fell through to the phone, but the score said
     * "passport". A mobile number is not proof of identity, which is the entire reason MSISDN is
     * weak, and the guard could be switched off by adding a field that matched nothing.
     */
    private double baseScore(InquiryRequest.SubmittedIdentifier matched) {
        return matched.type().isStrong() ? STRONG_IDENTIFIER_BASE : WEAK_IDENTIFIER_BASE;
    }

    private boolean sharesAnyToken(String left, String right) {
        String[] leftTokens = left.toLowerCase(Locale.ROOT).split(" ");
        String rightLower = " " + right.toLowerCase(Locale.ROOT) + " ";
        for (String token : leftTokens) {
            if (token.length() > 1 && rightLower.contains(" " + token + " ")) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
