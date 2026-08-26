package ai.dival.dip.modules.tix;

import ai.dival.dip.modules.tenants.TenantService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How big the network is, told to everybody in it, naming nobody.
 *
 * <p>DIP's argument is that one operator cannot see what several operators can. Every other figure
 * on the front door is about the caller's own book, which means the platform's whole differentiator
 * was the one thing the screen never showed. These five counts are that, and they are the only
 * numbers in the product that describe the exchange rather than a participant.
 *
 * <p><strong>Aggregate is not the same as harmless, and this class is where the difference is
 * decided.</strong> Every figure is a scalar produced by Postgres. Nothing here fetches a tenant
 * id, a subject row, or a pair of the two — not because the caller would misuse them, but because
 * a method that holds the ids and returns a count is one refactor away from returning the ids, and
 * the refusal has to live somewhere it cannot be undone by accident. The riskiest figure is
 * {@link Network#sharedSubjects()}: it measures how much overlap exists between operators, which
 * is the closest any published number comes to the boundary the exchange promises. It survives
 * because a total says the network has overlap and never which operators overlap with whom.
 *
 * <p><strong>The counts are of what an inquiry could actually reach.</strong> Records past their
 * retention date are excluded, merged subjects are counted once. A network advertised as larger
 * than the one an operator can query would be a marketing figure wearing a measurement's clothes,
 * and this platform has spent a lot of effort not doing that.
 *
 * <p>One transaction, one exchange-mode flag, four statements. Splitting them across transactions
 * would let the figures disagree with each other, and figures that disagree are how a reader
 * decides a dashboard is decorative.
 */
@Service
public class NetworkService {

    private final DebtRecordRepository debtRecords;
    private final TenantService tenants;
    private final EntityManager entityManager;
    private final Clock clock;

    public NetworkService(DebtRecordRepository debtRecords, TenantService tenants,
                          EntityManager entityManager, Clock clock) {
        this.debtRecords = debtRecords;
        this.tenants = tenants;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Opts this transaction into reading debt records across operators.
     *
     * <p>Deliberately a copy of {@link ExchangeService}'s method rather than a shared helper. The
     * flag relaxes tenant isolation, and a utility that turns it on — importable, mockable, three
     * characters to autocomplete — is exactly the shape of thing that ends up called from a write
     * path. Two callers, each stating in its own file why it is entitled to it, is cheaper than
     * one convenient one.
     *
     * <p>{@code SET LOCAL} semantics: scoped to the transaction, discarded at commit or rollback,
     * so it cannot survive onto a pooled connection and leak into the next request.
     */
    private void enterExchangeMode() {
        entityManager
                .createNativeQuery("SELECT set_config('app.exchange', 'on', true)")
                .getSingleResult();
    }

    /**
     * The network, as of now.
     *
     * <p>Read-only, and that is enforced rather than intended: exchange mode appears only in the
     * policy's USING clause, so even if this transaction tried to write outside its own tenant the
     * database would refuse it.
     *
     * <p><strong>{@code REQUIRES_NEW}, and it is the most important word in this file.</strong>
     * {@code SET LOCAL} scopes the flag to the transaction, not to the method — so joining a
     * caller's transaction would leave exchange mode on for everything that caller does
     * afterwards. Its first caller is {@link ai.dival.dip.modules.overview.OverviewService}, which
     * is itself {@code @Transactional} and, in the same breath, counts the operator's own
     * register. Under REQUIRED that register would begin counting every operator's records the
     * day somebody reordered two lines, with no error, no test failure and a number that looks
     * entirely ordinary. Relying on Java's left-to-right argument evaluation to keep a tenant
     * boundary is not a boundary.
     *
     * <p>The cost is a second connection for the duration of four counts. The
     * {@code TenantAwareDataSource} binds the caller's tenant as each connection is handed out, so
     * the new transaction is bound identically — exchange mode is the only thing that differs, and
     * it dies with the transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Network summarise() {
        enterExchangeMode();

        LocalDate today = LocalDate.now(clock);
        Instant startOfDay = today.atStartOfDay(clock.getZone()).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(clock.getZone()).toInstant();

        // Registered rather than contributing. An institution that has signed up and not yet
        // delivered a file is part of the network — it can be enquired of, it has accepted the
        // terms — and reporting only contributors would make the network appear to shrink every
        // time somebody's records aged out.
        long registered = tenants.list().stream().filter(t -> t.isActive()).count();

        List<Object[]> totals = debtRecords.networkTotals(today);
        Object[] row = totals.isEmpty() ? new Object[] {0L, 0L, 0L} : totals.get(0);

        return new Network(
                registered,
                asLong(row, 0),
                asLong(row, 1),
                asLong(row, 2),
                debtRecords.countSubjectsOwingTwoOrMoreOperators(today),
                debtRecords.countDeclaredBetween(startOfDay, startOfTomorrow));
    }

    /**
     * One column of an aggregate row, as a long.
     *
     * <p>Postgres returns {@code count()} as {@code BIGINT}, which the JDBC driver hands over as a
     * {@link Long} — but a native query's element type is {@code Object} and the mapping has been
     * different on other drivers. Going through {@link Number} means a driver that returns
     * {@code BigInteger} produces the right answer rather than a {@code ClassCastException} on a
     * screen that worked in every test.
     */
    private static long asLong(Object[] row, int column) {
        return row.length > column && row[column] instanceof Number value ? value.longValue() : 0L;
    }

    /**
     * The size of the exchange, in counts that cannot become names.
     *
     * @param institutions     participating operators, whether or not they have delivered anything
     * @param contributing     operators holding at least one record an inquiry could reach today
     * @param subjects         businesses and people the network holds a live record against
     * @param sectors          distinct sectors recorded against those subjects; see below
     * @param sharedSubjects   subjects owing two or more operators — the figure only a shared
     *                         registry can produce
     * @param declaredToday    records declared network-wide since midnight
     */
    public record Network(long institutions,
                          long contributing,
                          long subjects,
                          long sectors,
                          long sharedSubjects,
                          long declaredToday) {

        /**
         * Whether anybody has recorded a sector yet.
         *
         * <p>A subject's sector is free text an operator may map from a column of its own file, and
         * V32 says why it is not a code list: there is no Congolese sector taxonomy a telecom and
         * a bank would both recognise. Nothing populates it until an operator chooses to map it,
         * so zero here means "not being recorded" and not "no industries". The screen has to say
         * those differently, and a bare 0 says the wrong one.
         */
        public boolean sectorsRecorded() {
            return sectors > 0;
        }
    }
}
