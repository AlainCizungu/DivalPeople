package ai.dival.dip.modules.tix;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.RecordOrigin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What one operator is owed, aged and broken down.
 *
 * <p>Tenant-scoped without exception. Every figure here is counted from records the calling
 * operator declared itself; nothing crosses the exchange boundary, and there is no parameter that
 * could make it. A portfolio view that could be pointed at another operator would be a far more
 * valuable product and a completely different one.
 *
 * <p><strong>Amounts are never summed across currencies.</strong> Adding 500 USD to 500 CDF gives
 * 1,000 of nothing, and the resulting number looks entirely plausible on a dashboard. Every total
 * below is therefore per currency, including inside each aging band — which costs a nested
 * structure and is worth it.
 *
 * <p>Computed here rather than in the browser, which is where the overview page counts. Aging is
 * date arithmetic over money: it deserves a test that runs, and TypeScript in a user's timezone is
 * not somewhere that can be arranged.
 */
@Service
public class PortfolioService {

    private final DebtRecordRepository debtRecords;

    public PortfolioService(DebtRecordRepository debtRecords) {
        this.debtRecords = debtRecords;
    }

    /**
     * Summarises the calling operator's own records as at {@code today}.
     *
     * <p>Records past their retention date are excluded from every figure and counted separately
     * as {@link Summary#awaitingErasure}. They are due to be deleted by the nightly purge and, as
     * far as the exchange is concerned, already do not exist — a portfolio that still counted them
     * would show an operator exposure the law says it may no longer hold. Surfacing the count is
     * the point: a number that does not fall back to zero means the purge has stopped running.
     */
    @Transactional(readOnly = true)
    public Summary summarise(LocalDate today) {
        UUID tenantId = TenantContext.require();
        List<DebtRecord> records = debtRecords.findByTenantId(tenantId);

        Map<String, Money> exposure = new TreeMap<>();
        Map<AgingBand, BandTally> aging = new EnumMap<>(AgingBand.class);
        Map<DebtStatus, Integer> byStatus = new EnumMap<>(DebtStatus.class);
        Map<String, Integer> byService = new TreeMap<>();

        int awaitingErasure = 0;
        int imported = 0;
        int counted = 0;

        for (DebtRecord record : records) {
            if (record.isExpiredAsOf(today)) {
                awaitingErasure++;
                continue;
            }
            counted++;
            if (record.getOrigin() == RecordOrigin.IMPORT) {
                imported++;
            }

            DebtStatus status = record.getStatus();
            byStatus.merge(status, 1, Integer::sum);
            byService.merge(record.getServiceCategory(), 1, Integer::sum);

            String currency = record.getCurrency();
            BigDecimal amount = record.getAmount();
            exposure.computeIfAbsent(currency, key -> new Money()).add(status, amount);

            // Aging describes exposure, so a settled debt has no age worth showing: the question
            // it answers is "how long has this been unpaid", and the answer for a settled record
            // is that it is not. Counting them would make a portfolio look older the more of it
            // had been recovered, which is backwards.
            if (isUnsettled(status)) {
                aging.computeIfAbsent(AgingBand.of(record.getDefaultDate(), today),
                        band -> new BandTally()).add(currency, amount);
            }
        }

        return new Summary(
                today,
                counted,
                imported,
                awaitingErasure,
                exposureView(exposure),
                agingView(aging),
                statusView(byStatus),
                countView(byService));
    }

    private static boolean isUnsettled(DebtStatus status) {
        return status == DebtStatus.OUTSTANDING
                || status == DebtStatus.DISPUTED
                || status == DebtStatus.UNDER_INVESTIGATION;
    }

    private static List<CurrencyExposure> exposureView(Map<String, Money> exposure) {
        List<CurrencyExposure> view = new ArrayList<>();
        for (Map.Entry<String, Money> entry : exposure.entrySet()) {
            Money money = entry.getValue();
            view.add(new CurrencyExposure(
                    entry.getKey(),
                    money.outstanding.toPlainString(), money.outstandingCount,
                    money.contested.toPlainString(), money.contestedCount,
                    money.settled.toPlainString(), money.settledCount));
        }
        return List.copyOf(view);
    }

    /**
     * Every band, including the empty ones.
     *
     * <p>A distribution with the zeroes left out reads as though those ages do not exist, and the
     * shape of an aging profile is most of its meaning — a gap in the middle is information.
     */
    private static List<Band> agingView(Map<AgingBand, BandTally> aging) {
        List<Band> view = new ArrayList<>();
        for (AgingBand band : AgingBand.values()) {
            BandTally tally = aging.get(band);
            if (tally == null) {
                view.add(new Band(band, 0, List.of()));
                continue;
            }
            List<BandAmount> amounts = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : tally.byCurrency.entrySet()) {
                amounts.add(new BandAmount(entry.getKey(), entry.getValue().toPlainString()));
            }
            view.add(new Band(band, tally.count, List.copyOf(amounts)));
        }
        return List.copyOf(view);
    }

    private static List<StatusCount> statusView(Map<DebtStatus, Integer> byStatus) {
        List<StatusCount> view = new ArrayList<>();
        for (Map.Entry<DebtStatus, Integer> entry : byStatus.entrySet()) {
            view.add(new StatusCount(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(view);
    }

    private static List<LabelledCount> countView(Map<String, Integer> counts) {
        List<LabelledCount> view = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            view.add(new LabelledCount(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(view);
    }

    /** Running totals for one currency. Mutable on purpose; it never leaves this class. */
    private static final class Money {

        private BigDecimal outstanding = BigDecimal.ZERO;
        private BigDecimal contested = BigDecimal.ZERO;
        private BigDecimal settled = BigDecimal.ZERO;
        private int outstandingCount;
        private int contestedCount;
        private int settledCount;

        void add(DebtStatus status, BigDecimal amount) {
            switch (status) {
                case OUTSTANDING -> {
                    outstanding = outstanding.add(amount);
                    outstandingCount++;
                }
                case DISPUTED, UNDER_INVESTIGATION -> {
                    // Contested money is still money the operator believes it is owed. It is
                    // withheld from other operators, not from its own books, and folding it into
                    // "outstanding" would hide the fact that somebody is contesting it.
                    contested = contested.add(amount);
                    contestedCount++;
                }
                case SETTLED, CLEARED -> {
                    settled = settled.add(amount);
                    settledCount++;
                }
            }
        }
    }

    private static final class BandTally {

        private final Map<String, BigDecimal> byCurrency = new TreeMap<>();
        private int count;

        void add(String currency, BigDecimal amount) {
            byCurrency.merge(currency, amount, BigDecimal::add);
            count++;
        }
    }

    /**
     * The portfolio, as returned.
     *
     * @param recordCount     records counted, which is every live record — not only the unpaid
     *                        ones, so that a reader can tell a small portfolio from a well
     *                        recovered one
     * @param importedRecords how many came from a file rather than the API. Zero everywhere today,
     *                        and shown rather than hidden: it is the honest measure of how much of
     *                        the ingest pipeline is actually connected to the exchange
     * @param awaitingErasure past retention and not yet purged; should be zero every morning
     */
    public record Summary(
            LocalDate asOf,
            int recordCount,
            int importedRecords,
            int awaitingErasure,
            List<CurrencyExposure> exposure,
            List<Band> aging,
            List<StatusCount> byStatus,
            List<LabelledCount> byService) {
    }

    /** Amounts are strings: a total in centimes must not become a double on the way to a browser. */
    public record CurrencyExposure(
            String currency,
            String outstanding, int outstandingCount,
            String contested, int contestedCount,
            String settled, int settledCount) {
    }

    public record Band(AgingBand band, int count, List<BandAmount> amounts) {
    }

    public record BandAmount(String currency, String amount) {
    }

    public record StatusCount(DebtStatus status, int count) {
    }

    public record LabelledCount(String label, int count) {
    }
}
