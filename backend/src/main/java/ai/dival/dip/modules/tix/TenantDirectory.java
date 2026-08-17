package ai.dival.dip.modules.tix;

import ai.dival.dip.modules.tenants.TenantService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns a tenant id into an operator's name, for the one screen allowed to print one.
 *
 * <p>A tiny class, and it exists to be a chokepoint rather than a convenience. Resolving a tenant
 * name is two lines inlined anywhere; inlined anywhere is exactly the problem. The exchange spends
 * its life holding other operators' tenant ids and deliberately reducing them to a count, and the
 * moment turning one into a name is a one-liner available to every method, the rule that keeps them
 * anonymous stops being enforced by anything but habit. Everything that names an operator goes
 * through here, so "what can name an operator" is answerable by finding the callers of this class.
 *
 * <p><strong>This class does not decide whether naming is allowed.</strong> It answers the question
 * it is asked. {@link ExchangeService} consults {@link DisclosureProperties} and does not call this
 * at all when the answer is no — the check lives with the data, not with the lookup, so an empty
 * list is produced by never asking rather than by asking and discarding.
 *
 * <p>Reads through {@link TenantService}, not {@code TenantRepository}: the tenant table is another
 * module's storage, and reaching into it here would make every change to that schema a change to
 * the exchange.
 */
@Service
public class TenantDirectory {

    /** Shown instead of a name when the tenant registry has no row for an id. */
    static final String UNKNOWN = "—";

    private final TenantService tenants;

    public TenantDirectory(TenantService tenants) {
        this.tenants = tenants;
    }

    /**
     * Names each id, in the order given.
     *
     * <p>Takes the whole set rather than one id at a time so that a caller naming twelve operators
     * writes one call and not a loop with a query in it. Insertion-ordered, because the caller's
     * order is the order records were read in and the screen renders what it is handed.
     *
     * <p>A missing tenant resolves to {@link #UNKNOWN} rather than throwing. A deactivated operator
     * whose row was removed still has records in the exchange, and a profile screen that returns
     * 500 because one of five contributors was tidied up is worse than one that shows a dash.
     */
    public Map<UUID, String> namesOf(Iterable<UUID> tenantIds) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID id : tenantIds) {
            if (names.containsKey(id)) {
                continue;
            }
            try {
                names.put(id, tenants.get(id).getName());
            } catch (TenantService.TenantNotFoundException absent) {
                names.put(id, UNKNOWN);
            }
        }
        return names;
    }
}
