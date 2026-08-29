package ai.dival.dip.modules.users;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.modules.tenants.EmailDomainService;
import ai.dival.dip.modules.tenants.JoiningRules;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Somebody joining their own institution, without anybody creating an account for them.
 *
 * <p>The person registers with their work address, proves they can read mail at it, and this reads
 * the domain and looks up which institution it belongs to. <strong>They never say which
 * institution they are joining, and there is no parameter in which they could.</strong> That is the
 * same rule the rest of the platform follows — the tenant is decided by the server, never asserted
 * by the request — extended to the one moment where a person acts before any administrator has.
 *
 * <p><strong>They arrive with a tenant and no roles.</strong> That is the whole safety of this
 * design and it is worth being explicit about why. A verified {@code @vodacom.cd} address proves
 * employment; it does not prove authorisation. An intern has one. A marketing assistant has one.
 * Somebody who left last month may still have one. So joining grants membership and nothing else:
 * the person can sign in, and every screen tells them their access is pending, until an
 * administrator at their own institution decides what they may do. Approval is a real decision
 * rather than a formality, because it is the only thing standing between a company mailbox and
 * other institutions' credit records.
 *
 * <p><strong>The address must be verified.</strong> Unverified, it is a claim rather than a proof —
 * anybody can type any address into a registration form — and acting on it would let a stranger
 * join whichever institution they liked by typing the right domain. This refuses outright rather
 * than joining and waiting, because a half-joined state is a state somebody would eventually
 * approve out of.
 */
@Service
public class JoiningService {

    public static final String JOINED = "MEMBER_JOINED";
    public static final String JOIN_REFUSED = "MEMBER_JOIN_REFUSED";

    private static final Logger log = LoggerFactory.getLogger(JoiningService.class);

    private final IdentityAdminClient identity;
    private final EmailDomainService domains;
    private final AuditService audit;

    public JoiningService(IdentityAdminClient identity, EmailDomainService domains,
                          AuditService audit) {
        this.identity = identity;
        this.domains = domains;
        this.audit = audit;
    }

    /** Whether this deployment can write an institution onto an account at all. */
    public boolean available() {
        return identity.configured();
    }

    /**
     * Where the caller stands: already in an institution, joinable, or neither.
     *
     * <p>Read-only and side-effect free, so the screen can ask before offering anything. It
     * deliberately does not say which institution a joinable address would join — the person is
     * about to find out anyway, and answering it for an address that is not theirs would turn this
     * into a way to ask which company owns which domain, for anybody who can reach the endpoint.
     */
    public Standing standing() {
        Optional<Jwt> token = currentJwt();
        if (token.isEmpty()) {
            return new Standing(false, false, false, false);
        }
        Jwt jwt = token.get();

        boolean member = jwt.getClaimAsString("tenant_id") != null
                && !jwt.getClaimAsString("tenant_id").isBlank();
        String email = jwt.getClaimAsString("email");
        boolean verified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
        boolean joinable = !member && verified
                && JoiningRules.domainOf(email)
                        .flatMap(domains::institutionFor)
                        .isPresent();

        return new Standing(member, verified, email != null, joinable);
    }

    /**
     * Joins the institution that owns the caller's verified address.
     *
     * <p>Not {@code @Transactional}. The change is written at the identity provider and no database
     * transaction can roll it back; wrapping this in one would say otherwise.
     *
     * <p>Idempotent in the direction that matters: somebody who already belongs to an institution
     * is returned to it rather than moved. Re-running must never reassign a tenant — an account
     * that changed institutions would take its history with it, and the audit rows it wrote inside
     * the first one would become unexplainable.
     */
    public Outcome join(UUID actorId) {
        Jwt jwt = currentJwt().orElseThrow(() -> new PolicyRefusedException(
                "There is no signed-in account to join with."));

        String existing = jwt.getClaimAsString("tenant_id");
        if (existing != null && !existing.isBlank()) {
            // Already in. Deliberately not an error: a browser that retried, or a person who
            // opened the page twice, should not be told something went wrong.
            return new Outcome(true, false);
        }

        String email = jwt.getClaimAsString("email");
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            refuse(email, "address not verified");
            throw new PolicyRefusedException(
                    "Your email address has not been confirmed yet. Follow the link in the message "
                            + "sent when you registered, then sign in again. Until an address is "
                            + "confirmed it is something typed into a form rather than something "
                            + "you have shown you can read.");
        }

        String domain = JoiningRules.domainOf(email).orElseThrow(() -> {
            refuse(email, "no usable domain");
            return new PolicyRefusedException(
                    "That account has no work address on it, so there is nothing to identify an "
                            + "organisation by.");
        });

        UUID tenant = domains.institutionFor(domain).orElseThrow(() -> {
            refuse(email, "domain " + domain + " is not mapped");
            // Names the domain, never the institutions. Saying which organisations DO exist would
            // publish the participant list, which this platform withholds everywhere else.
            return new PolicyRefusedException(
                    "No organisation on DIP uses " + domain + ". If your organisation takes part, "
                            + "ask an administrator there to invite you directly — addresses at "
                            + "free mail providers always have to be invited.");
        });

        UUID subject = UUID.fromString(jwt.getSubject());
        String adminToken = identity.token();
        identity.assignTenant(adminToken, subject, tenant);

        // Recorded against the institution being joined, with the address, because "who appeared in
        // our organisation and when" is the first question an administrator asks about somebody
        // waiting for approval.
        audit.record(JOINED, "UserAccount", email, AuditService.OUTCOME_SUCCESS, actorId,
                "Joined via domain " + domain + "; no roles granted");
        log.info("An account joined an institution via domain {}", domain);

        return new Outcome(true, true);
    }

    private void refuse(String email, String why) {
        audit.record(JOIN_REFUSED, "UserAccount", email == null ? "unknown" : email,
                AuditService.OUTCOME_DENIED, null, why);
    }

    private Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(jwt);
    }

    /**
     * @param member    already belongs to an institution
     * @param verified  the address on the account has been confirmed
     * @param hasEmail  there is an address at all, which accounts made before this existed may not
     * @param joinable  an institution uses this address's domain, so joining would succeed
     */
    public record Standing(boolean member, boolean verified, boolean hasEmail, boolean joinable) {
    }

    /**
     * @param member       true once the caller belongs to an institution
     * @param signInAgain  true when something changed, because the tenant rides in the access token
     *                     and the one in the browser was minted before this. Nothing the person can
     *                     do in the application will work until they have a new one
     */
    public record Outcome(boolean member, boolean signInAgain) {
    }
}
