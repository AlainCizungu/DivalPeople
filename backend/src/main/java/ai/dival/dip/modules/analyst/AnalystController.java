package ai.dival.dip.modules.analyst;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analyst's one endpoint.
 *
 * <p>Guarded by the inquirer role rather than anything new, because that is what assembling a pack
 * does: it asks the exchange, once, charged and audited like any other question. Inventing an
 * "analyst" role would let somebody reach the exchange through a door the rate limiter and the
 * audit trail were not watching.
 *
 * <p>A GET that costs an allowance is unusual and is the right shape here anyway — the pack is a
 * read, it is idempotent in everything but the charge, and a POST would imply it changed something.
 * The charge is stated on the screen rather than hidden in the verb.
 */
@RestController
@RequestMapping("/api/v1/analyst")
public class AnalystController {

    private final EvidencePackService packs;
    private final CurrentUserService currentUser;

    public AnalystController(EvidencePackService packs, CurrentUserService currentUser) {
        this.packs = packs;
        this.currentUser = currentUser;
    }

    @GetMapping("/subject/{id}")
    @PreAuthorize("hasRole('" + Roles.TIX_INQUIRER + "')")
    public EvidencePackService.EvidencePack pack(
            @PathVariable UUID id,
            @RequestParam @NotBlank @Size(max = 300) String purpose) {
        return packs.forSubject(id, purpose, currentUser.currentUserIdOrNull());
    }
}
