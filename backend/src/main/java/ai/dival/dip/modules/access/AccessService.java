package ai.dival.dip.modules.access;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import ai.dival.dip.modules.users.UserAccount;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * What each role actually permits, read off the guards that enforce it.
 *
 * <p>Nobody could find out what {@code TIX_DECLARANT} means without reading Java. A tenant
 * administrator granting it was granting something they could not see the shape of, and a user
 * refused a screen was told only that they lacked permission — not which permission.
 *
 * <p><strong>Derived at runtime from {@code @PreAuthorize}, never written down.</strong> A
 * hand-maintained list of what each role can do is correct on the day it is written and wrong
 * from the first guard anybody changes, silently, in the direction of claiming access that no
 * longer exists. This reads the annotations Spring is actually enforcing, so the page cannot
 * disagree with the behaviour: they are the same thing.
 *
 * <p>The runtime part is load-bearing rather than convenient. A text search over the source finds
 * nothing for five of the thirteen roles, because controllers hoist their expressions into named
 * constants — {@code PayrollController.PAYROLL} is
 * {@code "hasAnyRole('" + PAYROLL_OFFICER + "', '" + FINANCE_OFFICER + "', ...)"} and the
 * annotation reads {@code @PreAuthorize(PAYROLL)}. Grepping that says PAYROLL_OFFICER guards
 * nothing, which is false and would have been a confident, wrong answer on a permissions screen.
 * By the time Spring holds the annotation the constant has been folded in, so the string here is
 * the resolved one.
 *
 * <p>Areas rather than endpoints, because a role that unlocks forty paths is not usefully
 * described by forty paths. The area is the segment after {@code /api/v1}, which is also how the
 * product is divided on screen.
 */
@Service
public class AccessService {

    /** {@code hasRole('X')} and {@code hasAnyRole('X', 'Y')} both reduce to quoted names. */
    private static final Pattern ROLE_IN_EXPRESSION = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    /**
     * Endpoints open to anybody signed in, gathered under a name of their own.
     *
     * <p>Not a role, and listed alongside them because the alternative is a catalogue that
     * silently omits a third of the API and leaves a reader to conclude those paths are
     * unreachable. Twelve endpoints are in this state and every one is a deliberate decision.
     */
    public static final String AUTHENTICATED = "AUTHENTICATED";

    private final RequestMappingHandlerMapping handlerMapping;
    private final CurrentUserService users;

    /**
     * Computed once and kept.
     *
     * <p>Handler mappings are fixed at startup, so rescanning per request would spend work to
     * produce an identical answer. Not eagerly initialised in the constructor either: this bean
     * and the mapping it reads are both created during context refresh, and asking for the
     * mappings too early is a startup-order dependency nobody would expect to find here.
     */
    private volatile List<RoleAccess> catalogue;

    public AccessService(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            CurrentUserService users) {
        this.handlerMapping = handlerMapping;
        this.users = users;
    }

    /**
     * @param canSeeMembers whether the caller may see who is in their organisation. The role
     *                      catalogue is shown to everybody; the list of people is not
     * @param myRoles       the caller's own roles, so the screen can mark which of these they hold
     */
    public Access forCaller(boolean canSeeMembers, List<String> myRoles) {
        List<Member> members = canSeeMembers
                ? users.listTenantMembers().stream().map(Member::from).toList()
                : null;

        List<RoleAccess> roles = new ArrayList<>();
        for (RoleAccess role : roleCatalogue()) {
            // How many people here hold it, and null rather than zero when the caller may not
            // know. Zero would read as "nobody has this", which is a fact about the organisation
            // rather than about the reader's permissions.
            Long heldBy = members == null ? null : members.stream()
                    .filter(member -> member.roles().contains(role.role()))
                    .count();
            roles.add(new RoleAccess(role.role(), role.endpoints(), role.areas(), heldBy,
                    myRoles.contains(role.role())));
        }
        return new Access(roles, members);
    }

    private List<RoleAccess> roleCatalogue() {
        List<RoleAccess> cached = catalogue;
        if (cached != null) {
            return cached;
        }

        // Every declared role starts present with nothing against it, so a role that guards no
        // endpoint appears with a count of zero rather than vanishing. A missing row reads as a
        // page that has not finished loading; a zero reads as a role that grants nothing, which
        // is a thing somebody should know before assigning it to a member of staff.
        Map<String, Map<String, Integer>> byRole = new TreeMap<>();
        for (String role : declaredRoles()) {
            byRole.put(role, new TreeMap<>());
        }
        byRole.put(AUTHENTICATED, new TreeMap<>());

        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapped
                : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = mapped.getValue();
            // Actuator and anything Spring contributes are not this platform's permissions.
            if (!handler.getBeanType().getName().startsWith("ai.dival.dip")) {
                continue;
            }
            String expression = guardOn(handler);
            if (expression == null) {
                continue;
            }
            String area = areaOf(mapped.getKey());
            if (area == null) {
                continue;
            }

            Set<String> guarding = rolesIn(expression);
            if (guarding.isEmpty()) {
                // No role named: open to anybody authenticated, which is its own entry.
                guarding = Set.of(AUTHENTICATED);
            }
            for (String role : guarding) {
                byRole.computeIfAbsent(role, key -> new TreeMap<>())
                        .merge(area, 1, Integer::sum);
            }
        }

        List<RoleAccess> built = new ArrayList<>();
        byRole.forEach((role, areas) -> {
            List<Area> listed = areas.entrySet().stream()
                    .map(entry -> new Area(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparing(Area::name))
                    .toList();
            int total = listed.stream().mapToInt(Area::endpoints).sum();
            built.add(new RoleAccess(role, total, listed, null, false));
        });

        catalogue = List.copyOf(built);
        return catalogue;
    }

    /** The method's own guard, or the controller's when the method carries none. */
    private String guardOn(HandlerMethod handler) {
        PreAuthorize onMethod = handler.getMethodAnnotation(PreAuthorize.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        PreAuthorize onType = AnnotatedElementUtils.findMergedAnnotation(
                handler.getBeanType(), PreAuthorize.class);
        return onType == null ? null : onType.value();
    }

    private Set<String> rolesIn(String expression) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = ROLE_IN_EXPRESSION.matcher(expression);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * The part of the product an endpoint belongs to.
     *
     * <p>{@code /api/v1/tix/inquiries} is "tix". Anything outside {@code /api/v1} is not part of
     * the product's permission surface and is skipped rather than filed under a heading nobody
     * would recognise.
     */
    private String areaOf(RequestMappingInfo info) {
        Set<String> patterns = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues()
                : info.getPatternsCondition() != null
                        ? info.getPatternsCondition().getPatterns()
                        : Set.of();
        for (String pattern : patterns) {
            if (!pattern.startsWith("/api/v1/")) {
                continue;
            }
            String rest = pattern.substring("/api/v1/".length());
            int slash = rest.indexOf('/');
            String area = slash < 0 ? rest : rest.substring(0, slash);
            if (!area.isBlank()) {
                return area;
            }
        }
        return null;
    }

    /**
     * The role names the platform declares.
     *
     * <p>Read reflectively off {@link Roles} so that adding a constant there is enough. The
     * alternative is a second list here that has to be remembered, and a role invented on a
     * Friday afternoon that no screen ever mentions.
     */
    private static List<String> declaredRoles() {
        List<String> names = new ArrayList<>();
        for (Field field : Roles.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    names.add((String) field.get(null));
                } catch (IllegalAccessException unreachable) {
                    throw new IllegalStateException(
                            "Roles constants are public; this cannot happen", unreachable);
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * @param roles   every declared role, and the authenticated-only pseudo-role
     * @param members people in the caller's organisation, or null when they may not see them
     */
    public record Access(List<RoleAccess> roles, List<Member> members) {
    }

    /**
     * @param endpoints how many endpoints this role unlocks. Zero is meaningful: the role exists
     *                  and grants nothing
     * @param heldBy    how many people here hold it, or null when the caller may not know
     * @param held      whether the caller holds it, so somebody refused a screen can see what
     *                  they are missing
     */
    public record RoleAccess(String role, int endpoints, List<Area> areas, Long heldBy,
                             boolean held) {
    }

    public record Area(String name, int endpoints) {
    }

    /** @param roles as recorded when this person last signed in, not as Keycloak holds them now */
    public record Member(String email, String displayName, List<String> roles, boolean active,
                         Instant lastSeenAt) {

        static Member from(UserAccount user) {
            return new Member(user.getEmail(), user.getDisplayName(), user.getRoleList(),
                    user.isActive(), user.getLastSeenAt());
        }
    }
}
