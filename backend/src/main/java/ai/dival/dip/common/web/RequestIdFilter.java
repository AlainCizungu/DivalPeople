package ai.dival.dip.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an identifier, so an audit row and a log line can be joined.
 *
 * <p>{@code audit_event} has had a {@code request_id} column since the first migration and nothing
 * ever wrote one — {@code AuditService} passed {@code null}. An audit trail that cannot be
 * correlated with the logs answers "what happened" and not "what else happened at the same time",
 * which is the question an investigation actually asks.
 *
 * <p>The id is generated here rather than taken from a request header. A client-supplied
 * correlation id would let a caller stamp every one of its requests with the same value, or with
 * somebody else's, which turns the audit log into something the audited party controls.
 */
@Component
@Order(50) // before TenantResolutionFilter, so a rejected tenant claim still gets an id
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "dip.requestId";

    /** The logging pattern can pick this up; nothing breaks if it does not. */
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Cleared in a finally for the same reason TenantContext is: these threads are pooled,
            // and a stale value here would attribute one person's request to another's identifier.
            MDC.remove(MDC_KEY);
        }
    }
}
