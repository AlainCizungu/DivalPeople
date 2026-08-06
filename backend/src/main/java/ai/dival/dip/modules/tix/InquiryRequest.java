package ai.dival.dip.modules.tix;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A verification request.
 *
 * <p>{@code purpose} is mandatory and recorded on the audit trail: every lookup must be able to
 * answer "why did you look this person up".
 */
public record InquiryRequest(
        @NotEmpty List<@NotNull SubmittedIdentifier> identifiers,
        String fullName,
        @NotBlank String purpose) {

    public record SubmittedIdentifier(@NotNull IdentifierType type, @NotBlank String value) {
    }
}
