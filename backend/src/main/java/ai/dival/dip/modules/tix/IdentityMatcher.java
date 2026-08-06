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
 */
@Component
public class IdentityMatcher {

    private static final double STRONG_IDENTIFIER_BASE = 0.90;
    private static final double WEAK_IDENTIFIER_BASE = 0.60;
    private static final double NAME_AGREEMENT_BONUS = 0.08;
    private static final double NAME_CONFLICT_PENALTY = 0.25;

    /**
     * @return confidence in the range 0.0–1.0
     */
    public double confidence(Subject subject, InquiryRequest request) {
        double score = baseScore(request);

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

    private double baseScore(InquiryRequest request) {
        boolean strong = request.identifiers().stream()
                .anyMatch(identifier -> identifier.type().isStrong());
        return strong ? STRONG_IDENTIFIER_BASE : WEAK_IDENTIFIER_BASE;
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
