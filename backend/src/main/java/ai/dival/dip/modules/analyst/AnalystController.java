package ai.dival.dip.modules.analyst;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analyst's two endpoints: ask a question, or assemble a pack about one company.
 *
 * <p>Both guarded by the inquirer role rather than anything new. Assembling a pack asks the
 * exchange, charged and audited like any other question; asking costs nothing by itself but can
 * quote a price. Inventing an "analyst" role would let somebody reach the exchange through a door
 * the rate limiter and the audit trail were not watching.
 */
@RestController
@RequestMapping("/api/v1/analyst")
public class AnalystController {

    private final EvidencePackService packs;
    private final AskService questions;
    private final CurrentUserService currentUser;

    public AnalystController(EvidencePackService packs, AskService questions,
                             CurrentUserService currentUser) {
        this.packs = packs;
        this.questions = questions;
        this.currentUser = currentUser;
    }

    /**
     * Ask a question in words.
     *
     * <p>POST rather than GET, and not for the usual reason. The question is a body because it is
     * free text a user typed and putting it in a query string would write it into every access log
     * on the way — and a question can name a company. Nothing is changed by asking, except the
     * audit row that records the asking, which is deliberate.
     *
     * <p>Guarded by the inquirer role. Asking costs nothing by itself; acting on the answer costs
     * inquiries, and the answer quotes the price rather than spending it.
     */
    @PostMapping("/ask")
    @PreAuthorize("hasRole('" + Roles.TIX_INQUIRER + "')")
    public AskService.Answer ask(@Valid @RequestBody AskRequest request) {
        return questions.ask(request.question(), currentUser.currentUserIdOrNull());
    }

    /** @param question what the user typed, verbatim */
    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    @GetMapping("/subject/{id}")
    @PreAuthorize("hasRole('" + Roles.TIX_INQUIRER + "')")
    public EvidencePackService.EvidencePack pack(
            @PathVariable UUID id,
            @RequestParam @NotBlank @Size(max = 300) String purpose) {
        return packs.forSubject(id, purpose, currentUser.currentUserIdOrNull());
    }
}
