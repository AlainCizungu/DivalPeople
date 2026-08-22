package ai.dival.dip.modules.tix;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The "continuous" in continuous monitoring.
 *
 * <p>Search tells an institution what the registry knows right now. Monitoring tells it when
 * something changes afterwards, and the difference is the whole commercial argument: one is a thing
 * employees occasionally do, the other is a thing an institution can depend on. Nothing about that
 * works while the sweep only runs when somebody presses a button — a monitoring feature you have to
 * remember to use is a search with extra steps.
 *
 * <p><strong>A slice per operator per night, not a full pass.</strong> Every check is a real
 * inquiry, charged to that operator's hourly allowance and written into its audit trail, so a bank
 * watching twelve thousand customers is checked over several nights rather than all at once. See
 * {@code WatchlistService.SWEEP_SLICE}. The alternative was exempting monitoring from the rate
 * limit, which would put a path to the exchange outside the control that exists to make sweeping it
 * legible.
 *
 * <p><strong>Per tenant, in its own transaction, and one failure does not stop the rest.</strong>
 * An operator whose sweep throws — a subject mid-erasure, a rate limit already spent by a busy
 * afternoon — must not silently cost every other operator its night's monitoring. The failure is
 * logged against the tenant it belongs to and the loop continues.
 *
 * <p>Runs as nobody. The actor on every audit row is null, which reads as "the platform did this"
 * and is true: no person asked for these inquiries, and attributing them to whoever last opened the
 * watch would put a name against work they did not do.
 */
@Component
public class MonitoringScanner {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScanner.class);

    private final TenantService tenants;
    private final WatchlistService watchlist;

    public MonitoringScanner(TenantService tenants, WatchlistService watchlist) {
        this.tenants = tenants;
        this.watchlist = watchlist;
    }

    /**
     * Nightly, after the retention purge and before the working day.
     *
     * <p>Deliberately after erasure. A subject whose records are past their retention period is
     * erased at 02:15, and sweeping first would raise alerts about companies the registry is about
     * to forget — telling an operator that its customer's position improved dramatically overnight
     * when what happened is that the evidence expired.
     */
    @Scheduled(cron = "${dip.tix.monitoring-sweep-cron:0 45 2 * * *}")
    public void sweepAll() {
        for (Tenant tenant : tenants.list()) {
            try {
                WatchlistService.Sweep swept =
                        TenantContext.runAsResult(tenant.getId(), () -> watchlist.sweep(null));
                if (swept.watched() > 0) {
                    log.info("Monitoring: {} checked {} of {} watch(es), {} changed",
                            tenant.getName(), swept.checked(), swept.watched(), swept.changed());
                }
            } catch (RuntimeException failed) {
                // Logged and swallowed, on purpose. One operator's bad night is not every
                // operator's, and a scheduler that stops at the first exception monitors whoever
                // happens to sort first.
                log.warn("Monitoring sweep failed for tenant {}", tenant.getName(), failed);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
