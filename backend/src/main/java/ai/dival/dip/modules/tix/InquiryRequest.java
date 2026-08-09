package ai.dival.dip.modules.tix;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A verification request.
 *
 * <p>{@code purpose} is mandatory and recorded on the audit trail: every lookup must be able to
 * answer "why did you look this person up".
 *
 * <p><strong>An identifier or a name, and either will do.</strong> Identifiers used to be
 * mandatory, which was right for the exchange's safety and wrong for the person using it: a credit
 * officer holding a company's letterhead has a trading name and not an RCCM number, and refusing
 * to answer meant they went and asked somebody by telephone instead. Name resolution is
 * deliberately narrow — exact match on the normalised name, and only when precisely one subject in
 * the registry carries it — and a bare personal name never clears the confidence threshold on its
 * own. See {@link ExchangeService} and {@link IdentityMatcher}.
 *
 * <p>A name is not a search term. It resolves to one subject or to none, the answer is a status
 * rather than a list, and the request is rate-limited and audited like any other. That is the
 * difference between a lookup and a way to enumerate the registry.
 */
public record InquiryRequest(
        List<@NotNull SubmittedIdentifier> identifiers,
        String fullName,
        @NotBlank String purpose) {

    /**
     * Short enough to match a large part of the registry, and therefore not a name.
     *
     * <p>Four rather than two, unlike the search box: that one is confined to the caller's own
     * book, and this one is not.
     */
    private static final int MINIMUM_NAME_LENGTH = 4;

    public InquiryRequest {
        // Never null downstream, so resolution can iterate without a guard at every call site.
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
    }

    /**
     * Whether there is anything here to resolve.
     *
     * <p>Expressed as a validation rule rather than as two {@code @NotEmpty} annotations, because
     * the requirement is a disjunction: neither field is individually required and one of them
     * has to be there.
     */
    @AssertTrue(message = "Give at least one identifier, or a name of at least four characters. "
            + "An inquiry with neither has nothing to resolve.")
    public boolean isResolvable() {
        return !identifiers.isEmpty() || hasUsableName();
    }

    /** A name long enough to be worth resolving on its own. */
    public boolean hasUsableName() {
        return fullName != null && fullName.trim().length() >= MINIMUM_NAME_LENGTH;
    }

    public record SubmittedIdentifier(@NotNull IdentifierType type, @NotBlank String value) {
    }
}
