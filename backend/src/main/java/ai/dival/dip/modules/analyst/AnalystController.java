package ai.dival.dip.modules.analyst;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint: ask a question.
 *
 * <p>There were two. The second served an evidence pack for a company chosen from a search box, and
 * it went when the screen did — the pack is now the answer to "why is this company risky", which is
 * how somebody would ask for it in words. An endpoint nothing calls is API surface with no user,
 * and adding it back is three lines if a drill-down ever wants it.
 *
 * <p>Guarded by the inquirer role rather than anything new. Asking costs nothing by itself; the
 * answers that reach the exchange are charged and audited exactly like any other inquiry, and the
 * ones that would cost several quote the price rather than spending it. Inventing an "analyst" role
 * would let somebody reach the exchange through a door the rate limiter was not watching.
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

}
