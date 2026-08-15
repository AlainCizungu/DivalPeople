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
 * @param profile         sector, city and street, or null when the declaration says none of it
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
        boolean dunningEvidence,
        @Valid Profile profile) {

    /**
     * Everything an operator can say about a company beyond its name and its documents.
     *
     * <p>One component rather than three, and nullable, because most declarations will not carry
     * it: an operator posting from its billing system has an account number and an amount, and the
     * sector and address arrive on a file prepared against the published template. A record whose
     * arity grew by three would have made every existing caller say {@code null, null, null}, which
     * reads as an oversight rather than as an absence.
     *
     * @param sector        line of business, free text — there is no taxonomy both a telecom and a
     *                      bank already hold, and inventing one would mean every operator mapping
     *                      their vocabulary onto ours before a single row imported
     * @param city          city or commune, compared as an equality
     * @param streetAddress street, compared loosely and weighed at nothing when it differs
     */
    public record Profile(@Size(max = 120) String sector, @Size(max = 120) String city,
                          @Size(max = 300) String streetAddress) {
    }

    /**
     * A declaration that says nothing about the company beyond its name and its documents.
     *
     * <p>The common case today and the reason this constructor exists rather than making
     * twenty-nine call sites pass a null. Kept explicit: somebody reading a call has to be able to
     * see that no profile was supplied, and a shorter signature says that more clearly than three
     * trailing nulls.
     */
    public DeclarationRequest(List<SubmittedIdentifier> identifiers, String fullName,
                              Subject.SubjectType subjectType, LocalDate dateOfBirth,
                              String nationality, BigDecimal amount, String currency,
                              String serviceCategory, LocalDate defaultDate,
                              boolean dunningEvidence) {
        this(identifiers, fullName, subjectType, dateOfBirth, nationality, amount, currency,
                serviceCategory, defaultDate, dunningEvidence, null);
    }

    public record SubmittedIdentifier(@NotNull IdentifierType type, @NotBlank String value) {
    }

    public Subject.SubjectType subjectTypeOrDefault() {
        return subjectType == null ? Subject.SubjectType.INDIVIDUAL : subjectType;
    }
}
