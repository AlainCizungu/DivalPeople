package ai.dival.dip.common.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant of the authenticated principal to {@link TenantContext} for the request.
 *
 * <p>The tenant is read from the {@code tenant_id} claim of the validated access token. Client
 * supplied values are ignored by design: a request may not choose its own tenant.
 */
@Component
@Order(TenantResolutionFilter.ORDER)
public class TenantResolutionFilter extends OncePerRequestFilter {

    /** Runs after Spring Security has populated the SecurityContext. */
    public static final int ORDER = 100;

    public static final String TENANT_CLAIM = "tenant_id";

    private static final Logger log = LoggerFactory.getLogger(TenantResolutionFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            resolveTenant().ifPresent(TenantContext::set);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private java.util.Optional<UUID> resolveTenant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return java.util.Optional.empty();
        }
        String claim = jwt.getClaimAsString(TENANT_CLAIM);
        if (claim == null || claim.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(claim));
        } catch (IllegalArgumentException ex) {
            // Do not log the claim value itself.
            log.warn("Rejected access token carrying a malformed {} claim", TENANT_CLAIM);
            return java.util.Optional.empty();
        }
    }
}
