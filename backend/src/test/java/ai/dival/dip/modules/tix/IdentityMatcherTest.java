package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for identity matching. No database, no container — these run everywhere.
 */
class IdentityMatcherTest {

    private final IdentityMatcher matcher = new IdentityMatcher();

    private final Subject stored = new Subject(
            Subject.SubjectType.INDIVIDUAL, "Jean Baptiste Kabila", LocalDate.of(1990, 5, 12), "CD");

    @Test
    @DisplayName("a strong identifier with an exact name clears the automatic threshold")
    void strongIdentifierWithExactName() {
        double confidence = score(IdentifierType.NATIONAL_ID, "CD-1234-5678", "Jean Baptiste Kabila");

        assertThat(confidence).isGreaterThanOrEqualTo(ExchangeService.AUTOMATIC_MATCH_THRESHOLD);
    }

    @Test
    @DisplayName("a phone number alone stays below the automatic threshold")
    void phoneNumberAloneRequiresReview() {
        double confidence = score(IdentifierType.MSISDN, "+243900000000", null);

        assertThat(confidence).isLessThan(ExchangeService.AUTOMATIC_MATCH_THRESHOLD);
    }

    @Test
    @DisplayName("a completely different name pulls confidence below the threshold")
    void conflictingNameLowersConfidence() {
        double confidence = score(IdentifierType.NATIONAL_ID, "CD-1234-5678", "Marie Ilunga");

        assertThat(confidence).isLessThan(ExchangeService.AUTOMATIC_MATCH_THRESHOLD);
    }

    @Test
    @DisplayName("accents and spacing do not change a name match")
    void nameNormalizationIgnoresAccentsAndSpacing() {
        assertThat(Subject.normalizeName("  Jean-Baptiste   KABILA "))
                .isEqualTo(Subject.normalizeName("Jean-Baptiste Kabila"));
        assertThat(Subject.normalizeName("Réné Mukendi")).isEqualTo("rene mukendi");
    }

    @Test
    @DisplayName("identifier normalization ignores separators and case")
    void identifierNormalization() {
        assertThat(SubjectIdentifier.normalizeValue("ab-123 456"))
                .isEqualTo(SubjectIdentifier.normalizeValue("AB123456"));
    }

    @Test
    @DisplayName("confidence is always within 0.0 and 1.0")
    void confidenceIsBounded() {
        double low = score(IdentifierType.MSISDN, "+243900000000", "Totally Different");
        double high = score(IdentifierType.PASSPORT, "X1", "Jean Baptiste Kabila");

        assertThat(low).isBetween(0.0, 1.0);
        assertThat(high).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("an invented strong identifier does not upgrade a phone-number match")
    void bogusStrongIdentifierDoesNotUpgradeAWeakMatch() {
        // The bypass. A caller who knows only a mobile number adds a passport number that matches
        // nothing: resolution falls through to the phone, but the score used to read "passport"
        // because it asked whether ANY submitted identifier had a strong type. A phone number is
        // not proof of identity, which is the whole reason MSISDN is weak.
        InquiryRequest request = new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(IdentifierType.PASSPORT, "ZZZZZZZZ"),
                        new InquiryRequest.SubmittedIdentifier(
                                IdentifierType.MSISDN, "+243900000000")),
                null, "ONBOARDING_CHECK");

        InquiryRequest.SubmittedIdentifier whatActuallyMatched =
                new InquiryRequest.SubmittedIdentifier(IdentifierType.MSISDN, "+243900000000");

        assertThat(matcher.confidence(stored, request, whatActuallyMatched))
                .isLessThan(ExchangeService.AUTOMATIC_MATCH_THRESHOLD);
    }

    @Test
    @DisplayName("the score is not in the response at all")
    void theScoreDoesNotLeave() {
        // A caller could otherwise submit one guessed name at a time and read the answer off the
        // number, recovering a competitor's subject's legal name token by token.
        for (var component : InquiryResult.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("InquiryResult." + component.getName() + " must not be a raw score")
                    .isNotEqualTo(double.class);
        }
    }

    private double score(IdentifierType type, String value, String fullName) {
        InquiryRequest request = request(type, value, fullName);
        return matcher.confidence(stored, request, request.identifiers().get(0));
    }

    private InquiryRequest request(IdentifierType type, String value, String fullName) {
        return new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(type, value)),
                fullName,
                "ONBOARDING_CHECK");
    }
}
