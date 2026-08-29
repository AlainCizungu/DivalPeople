package ai.dival.dip.modules.tenants;

import ai.dival.dip.common.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which email domains identify which institution.
 *
 * <p>Platform administrator only, on the class, and that guard is doing more work than most on this
 * platform. These three endpoints decide who can join which institution, which is upstream of every
 * other access control DIP has: a wrong row here puts a stranger inside a book, and no later check
 * would catch it, because from that point on they are a legitimate member of the wrong tenant.
 *
 * <p>Deliberately not offered to a tenant administrator for their own institution, which would be
 * the convenient thing. Nothing stops them claiming a competitor's domain, and the reward is every
 * future joiner from that competitor arriving in their book. When DNS verification exists this can
 * be delegated; a self-service claim without proof cannot.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants/{tenantId}/email-domains")
@PreAuthorize("hasRole('" + Roles.PLATFORM_ADMIN + "')")
public class EmailDomainController {

    private final EmailDomainService domains;

    public EmailDomainController(EmailDomainService domains) {
        this.domains = domains;
    }

    @GetMapping
    public List<String> list(@PathVariable UUID tenantId) {
        return domains.forTenant(tenantId);
    }

    /** Maps a domain, or refuses with a sentence naming the institution that already holds it. */
    @PostMapping
    public Mapped map(@PathVariable UUID tenantId, @Valid @RequestBody MapRequest request) {
        return new Mapped(domains.map(tenantId, request.domain()));
    }

    /**
     * Stops a domain identifying this institution.
     *
     * <p>Nobody already inside loses anything: the mapping governs joining, and somebody who joined
     * last year has their institution recorded against their account at the identity provider.
     * Removing access from people who are already in is a separate and deliberate act.
     */
    @DeleteMapping
    public void unmap(@PathVariable UUID tenantId,
                      @RequestParam @NotBlank @Size(max = 253) String domain) {
        domains.unmap(tenantId, domain);
    }

    public record MapRequest(@NotBlank @Size(max = 253) String domain) {
    }

    /** @param domain as stored, which is lower-cased and may differ from what was typed */
    public record Mapped(String domain) {
    }
}
