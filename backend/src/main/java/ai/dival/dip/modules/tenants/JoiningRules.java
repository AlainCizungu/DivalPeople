package ai.dival.dip.modules.tenants;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which institution an email address belongs to, and what an address has to be before the question
 * is even asked.
 *
 * <p>Static and dependency-free, for the same reason {@code MembershipRules} and
 * {@code ObligationHistory} are: this decides which institution's credit records a stranger can
 * see, and a decision of that weight should be exercisable at its edges without standing up a
 * database and an identity provider. Every awkward case below is a test, and every one of them was
 * cheaper to find here than in a browser.
 *
 * <p><strong>The thing this class exists to prevent.</strong> A person registering must not be able
 * to choose their institution. They supply an address, prove they can read mail at it, and the
 * domain is looked up. There is no parameter anywhere in the join path that names a tenant, which
 * is stronger than validating one — the same rule the rest of the platform follows, applied to the
 * one moment where the person acts before any administrator has.
 */
public final class JoiningRules {

    /**
     * Domains nobody can be an employee of.
     *
     * <p>Not a completeness effort — the list of free mail providers is endless and this is not the
     * defence. It refuses the handful an operator might plausibly type into the domain mapping by
     * mistake, in the minute they are onboarding an institution whose staff really do use gmail.
     * Mapping one of these would hand every Gmail user on earth an account inside that
     * institution's book, and the mistake would look like a working configuration.
     *
     * <p>The real defence is that the mapping is written by the platform operator and every joiner
     * lands with no roles.
     */
    private static final Set<String> NEVER_AN_INSTITUTION = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.fr", "hotmail.com", "hotmail.fr",
            "outlook.com", "outlook.fr", "live.com", "icloud.com", "me.com", "aol.com",
            "protonmail.com", "proton.me", "gmx.com", "mail.com", "yandex.com", "zoho.com");

    private JoiningRules() {
    }

    /**
     * The domain part of an address, lower-cased, or empty if this is not an address.
     *
     * <p>Deliberately shallow about what precedes the at-sign — local parts are far stranger than
     * most validators believe and rejecting a real one costs a customer. What it is strict about is
     * that there is exactly one at-sign and something on both sides, because "exactly one" is what
     * makes {@code lastIndexOf} and {@code indexOf} agree, and disagreeing is how
     * {@code a@b@evil.com} gets read as belonging to {@code b}.
     */
    public static Optional<String> domainOf(String email) {
        String cleaned = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

        int at = cleaned.indexOf('@');
        if (at <= 0 || at != cleaned.lastIndexOf('@') || at == cleaned.length() - 1) {
            return Optional.empty();
        }

        String domain = cleaned.substring(at + 1);
        return isPlausibleDomain(domain) ? Optional.of(domain) : Optional.empty();
    }

    /**
     * A domain as it should be stored, or empty if it could not be one.
     *
     * <p>Accepts what an operator will actually paste: a leading at-sign, a trailing dot, mixed
     * case, surrounding whitespace. Refusing those would be technically correct and would produce a
     * support conversation about an invisible character.
     */
    public static Optional<String> normaliseDomain(String domain) {
        String cleaned = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);

        if (cleaned.startsWith("@")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        return isPlausibleDomain(cleaned) ? Optional.of(cleaned) : Optional.empty();
    }

    /**
     * Whether an institution may be identified by this domain at all.
     *
     * <p>Separate from whether it is shaped like a domain, because the two refusals mean different
     * things to whoever is reading the error: one is a typo and one is a decision.
     */
    public static boolean canIdentifyAnInstitution(String normalisedDomain) {
        return normalisedDomain != null && !NEVER_AN_INSTITUTION.contains(normalisedDomain);
    }

    /** The free-mail domains this refuses, for a message that says which. */
    public static Set<String> neverAnInstitution() {
        return NEVER_AN_INSTITUTION;
    }

    /**
     * Whether a verified address matches a mapped domain.
     *
     * <p><strong>Exact, never a suffix.</strong> A suffix match would seem generous and would let
     * {@code payroll@vodacom.cd.attacker.example} into Vodacom's book, because that string does end
     * with something that ends with {@code vodacom.cd}. Even a dot-anchored suffix — matching
     * anything ending in {@code .vodacom.cd} — grants every subdomain the institution has ever
     * delegated, including ones delegated to a contractor years ago. If a company needs
     * {@code mail.vodacom.cd} as well, that is a second row somebody adds deliberately.
     */
    public static boolean matches(String mappedDomain, String addressDomain) {
        return mappedDomain != null && mappedDomain.equals(addressDomain);
    }

    /**
     * Shaped like a domain: at least one dot, no at-sign, no spaces, sane length, and no empty
     * label.
     *
     * <p>Not a validating regular expression. The set of strings that are really a domain is bigger
     * than any pattern short enough to read, and an over-strict one rejects a customer while a
     * loose one is caught by the fact that the mapping is written by hand and the address is proven
     * by email.
     */
    private static boolean isPlausibleDomain(String domain) {
        if (domain.length() < 4 || domain.length() > 253) {
            return false;
        }
        if (domain.indexOf('@') >= 0 || domain.indexOf(' ') >= 0 || domain.indexOf('\t') >= 0) {
            return false;
        }
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) {
            return false;
        }
        return domain.indexOf('.') > 0;
    }
}
