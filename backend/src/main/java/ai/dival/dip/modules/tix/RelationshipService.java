package ai.dival.dip.modules.tix;

import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting what happens to an account, and reading back what it adds up to.
 *
 * <p>The write half of DIP's answer to "your data is only negative". An operator reports events —
 * opened, performing, paid as agreed, late, restructured, defaulted, settled, closed — and the
 * account's history is whatever those events say. Nothing here stores a status or a score; see
 * {@link ObligationHistory}, which is the rule, kept static so it can be argued with.
 *
 * <p><strong>No reporting threshold.</strong> The floor on declarations exists to keep trivial
 * amounts out of a blacklist. An account paid as agreed carries no such risk, and a floor on
 * positive events would only make well-behaved small accounts invisible — which is backwards, and
 * would quietly reintroduce the bias this whole model exists to remove.
 */
@Service
public class RelationshipService {

    private final RelationshipRepository relationships;
    private final RelationshipEventRepository events;
    private final EntityManager entityManager;
    private final RetentionPolicy retention;
    private final Clock clock;

    public RelationshipService(RelationshipRepository relationships,
                               RelationshipEventRepository events,
                               EntityManager entityManager,
                               RetentionPolicy retention,
                               Clock clock) {
        this.relationships = relationships;
        this.events = events;
        this.entityManager = entityManager;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * Records something that happened to one of this operator's accounts.
     *
     * <p>Finds the account by the operator's own reference, or opens it. Opening on first sight is
     * deliberate: a telecom's first delivery is a book of accounts that already exist, and
     * requiring an explicit OPENED event before any other would make the first file fail entirely
     * or force the importer to invent an opening date it does not have.
     *
     * @param openedOn used only when the account is being created; ignored for one that exists
     */
    @Transactional
    public RelationshipEvent report(Subject subject, String accountReference, String product,
                                    String currency, LocalDate openedOn, ObligationEvent code,
                                    LocalDate occurredOn, DateSource dateSource, UUID rawRecordId) {
        UUID tenant = TenantContext.require();

        Relationship account = relationships
                .findByTenantIdAndAccountReference(tenant, accountReference)
                .orElseGet(() -> relationships.save(new Relationship(
                        subject, accountReference, product, currency, openedOn,
                        // INTERIM. §4 of docs/CREDIT_INTELLIGENCE.md is explicit that retention on
                        // positive history is a decision for Olivier and probably for the BCC: too
                        // short destroys the feature, indefinite is a permanent behavioural record
                        // of every subscriber. Borrowing the adverse rule keeps the column honest
                        // and keeps the question open — it is deliberately not a new invented
                        // constant, because a number that looks considered is harder to revisit
                        // than one that visibly belongs to something else.
                        retention.expiryFor(openedOn, false))));

        RelationshipEvent recorded = events.save(new RelationshipEvent(
                account, code, occurredOn, dateSource, rawRecordId));

        if (code.closesTheAccount()) {
            account.closeOn(occurredOn);
        }

        return recorded;
    }

    /** This operator's own accounts against a subject. No exchange mode: this is your own book. */
    @Transactional(readOnly = true)
    public List<Relationship> ownAccounts(UUID subjectId) {
        return relationships.findByTenantIdAndSubjectId(TenantContext.require(), subjectId);
    }

    /**
     * What the whole network's accounts say about one subject.
     *
     * <p>{@code REQUIRES_NEW} for the reason {@code NetworkService} spells out at length: exchange
     * mode is {@code SET LOCAL}, so it belongs to a transaction rather than to a method. Joining a
     * caller's transaction would leave cross-operator reads switched on for whatever that caller
     * does next, and the failure would be silent, plausible, and a tenant boundary decided by
     * statement order.
     *
     * <p>Returns counts and nothing else. The accounts are read here, reduced here, and the
     * institution identities never leave — {@link ObligationHistory} takes an opaque key purely to
     * count distinct contributors, and publishes a number.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ObligationHistory historyAcrossNetwork(UUID subjectId) {
        entityManager
                .createNativeQuery("SELECT set_config('app.exchange', 'on', true)")
                .getSingleResult();

        LocalDate today = LocalDate.now(clock);
        List<ObligationHistory.Account> accounts = new ArrayList<>();

        for (Relationship account : relationships.findAcrossOperatorsWithEvents(subjectId, today)) {
            accounts.add(new ObligationHistory.Account(
                    // The tenant id, used only to count distinct institutions and never returned.
                    account.getTenantId().toString(),
                    account.getEvents().stream().map(RelationshipEvent::getCode).toList()));
        }

        return ObligationHistory.of(accounts);
    }

    /** For the importer, which needs to know whether it is about to open an account or extend one. */
    @Transactional(readOnly = true)
    public Optional<Relationship> findOwn(String accountReference) {
        return relationships.findByTenantIdAndAccountReference(
                TenantContext.require(), accountReference);
    }
}
