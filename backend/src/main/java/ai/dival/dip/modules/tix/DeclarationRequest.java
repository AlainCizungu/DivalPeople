package ai.dival.dip.modules.tix;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An operator declaring that a subscriber has defaulted.
 *
 * <p>This is the writing end of the exchange and it did not exist until August 2026: the module
 * shipped with endpoints to list, settle and dispute records and no way to create one, so the
 * registry could only ever be filled by the development seeder. Every other property the exchange
 * claims — matching, thresholds, retention — was theoretical until an operator could put something
 * in.
 *
 * <p>The subject is described rather than referenced. A declaring operator knows its own
 * subscriber's documents; it does not and must not know the exchange's internal id for that
 * person, because handing out subject ids is how an operator learns who else is in the registry.
 * The exchange resolves the identifiers to a subject, or creates one.
 *
 * @param identifiers     documents identifying the subscriber; at least one
 * @param fullName        as held by the declaring operator, used for matching and for a subject's
 *                        own access request — never returned to an enquiring operator
 * @param subjectType     individual or business; defaults to individual when absent
 * @param dateOfBirth     optional, and a strong secondary discriminator when present
 * @param nationality     ISO 3166-1 alpha-2, optional
 * @param amount          outstanding, which must clear the reporting threshold for its currency
 * @param currency        ISO 4217; a currency with no configured threshold is refused
 * @param serviceCategory what the debt is for, e.g. "postpaid-voice"
 * @param defaultDate     when the obligation fell due — the start of the retention clock, not the
 *                        declaration date, so an operator cannot refresh a record's age by
 *                        re-declaring it
 * @param dunningEvidence assertion that the contractual dunning process ran first
 */
public record DeclarationRequest(
        @NotEmpty @Valid List<@NotNull SubmittedIdentifier> identifiers,
        @NotBlank @Size(max = 300) String fullName,
        Subject.SubjectType subjectType,
        LocalDate dateOfBirth,
        @Size(min = 2, max = 2) String nationality,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank @Size(max = 60) String serviceCategory,
        @NotNull LocalDate defaultDate,
        boolean dunningEvidence) {

    public record SubmittedIdentifier(@NotNull IdentifierType type, @NotBlank String value) {
    }

    public Subject.SubjectType subjectTypeOrDefault() {
        return subjectType == null ? Subject.SubjectType.INDIVIDUAL : subjectType;
    }
}
