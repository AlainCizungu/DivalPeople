package ai.dival.dip.modules.resolution;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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
 * The Identity Resolution Center.
 *
 * <p><strong>Every endpoint requires {@code PLATFORM_ADMIN}</strong>, and that is the security
 * decision the whole feature rests on rather than an administrative convenience. A candidate pair
 * is one operator's record beside another's, complete with both names — so a participant with
 * access to this queue would be reading a competitor's customer file, one review at a time. The
 * exchange spends a great deal of effort ensuring an inquiry discloses a count and a status and
 * nothing else; a resolution screen open to participants would give all of it away through a
 * different door.
 *
 * <p>The registry resolves. Participants receive the result as a better match.
 */
@RestController
@RequestMapping("/api/v1/resolution")
@PreAuthorize("hasRole('" + Roles.PLATFORM_ADMIN + "')")
public class EntityResolutionController {

    private static final int DEFAULT_PAGE = 50;

    private final EntityResolutionService resolution;
    private final CurrentUserService currentUser;

    public EntityResolutionController(EntityResolutionService resolution,
                                      CurrentUserService currentUser) {
        this.resolution = resolution;
        this.currentUser = currentUser;
    }

    @GetMapping("/candidates")
    public List<EntityResolutionService.Case> open(
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        return resolution.open(Math.clamp(limit, 1, 200));
    }

    @GetMapping("/candidates/{id}")
    public EntityResolutionService.Case one(@PathVariable UUID id) {
        return resolution.get(id);
    }

    /**
     * Looks for new candidates.
     *
     * <p>A POST because it writes: it opens cases. Manual for now and meant to be nightly, which
     * is a scheduler away — deliberately not scheduled yet, because a job that fills a queue
     * nobody has ever emptied is a job that produces a backlog rather than a feature.
     */
    @PostMapping("/scan")
    public EntityResolutionService.Scan scan() {
        return resolution.scan(actorId());
    }

    /**
     * Records a decision, and performs the merge when the decision is that they are one subject.
     *
     * <p>The three outcomes are the reviewer's, and the third is the one that matters: somebody
     * who cannot tell says so. A queue offering only confirm and reject pushes that answer into
     * "reject", because rejecting feels safer, and the pair leaves looking decided.
     */
    @PostMapping("/candidates/{id}/decision")
    public EntityResolutionService.Decision decide(@PathVariable UUID id,
                                                   @RequestBody DecisionRequest request) {
        return resolution.decide(id, request.outcome(), request.note(), actorId());
    }

    /**
     * @param outcome what the reviewer concluded; never OPEN, which the service refuses
     * @param note    what they saw. Required on every outcome, including a confirmation
     */
    public record DecisionRequest(@NotNull MatchStatus outcome, @NotNull String note) {
    }

    /**
     * Who is deciding.
     *
     * <p>A platform administrator runs the network and may have no local user record, so this can
     * be null — and the service refuses a decision that names nobody rather than accepting one.
     * Merging two people's files anonymously is not something the platform should be able to do.
     */
    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }
}
