package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a person may ask the exchange about themselves, and what happens when they do.
 *
 * <p><strong>The hard part is that rights are not tenant-scoped and the data is.</strong> A person
 * appears in the registry because several operators declared against them; their right of access
 * is to the whole file, not to whichever operator's office they happened to walk into. But
 * {@code tix_debt_record} carries a row-level security policy whose WITH CHECK clause allows an
 * operator to write only its own rows, deliberately and correctly — exchange mode relaxes reads
 * and never writes.
 *
 * <p>So effects are applied by binding each tenant in turn and letting it act on its own records,
 * the same shape as {@link RetentionPurge}. It is slower than a single cross-tenant statement and
 * it keeps the boundary a boundary. The alternative — a policy permitting cross-tenant writes —
 * would exist for every future query as well as this one.
 *
 * <p><strong>No method here is {@code @Transactional}, and that is the whole design rather than an
 * omission.</strong> {@link ai.dival.dip.common.tenancy.TenantAwareDataSource} binds the tenant
 * when a connection is checked out, so a transaction is pinned to whichever tenant was current
 * when it began. Annotating these methods — which they were until August 2026 — meant the
 * per-tenant blocks below joined the caller's transaction, kept the caller's connection, and kept
 * the caller's tenant binding. Under row-level security as the unprivileged {@code dip_app} role,
 * the cross-operator suppression, erasure and disclosure would have touched only the calling
 * operator's rows: a person's "whole file" would have contained one operator, and a dispute would
 * have suppressed nothing anywhere else.
 *
 * <p>Nothing failed, because the integration tests connect as the schema owner and bypass
 * row-level security entirely. {@code RowLevelSecurityTest} is where it shows, and it now has a
 * case for it.
 *
 * <p>So the work is split into units, each in its own {@code REQUIRES_NEW} transaction, and the
 * order of those units is load-bearing:
 *
 * <ul>
 *   <li>the case is committed <em>before</em> any record is suppressed, because
 *       {@code suppressed_by_request_id} is a foreign key to it;</li>
 *   <li>the decision is committed <em>before</em> anybody is notified, because telling
 *       institutions a record was withdrawn and then rolling that back is worse than silence;</li>
 *   <li>the suppression is read <em>before</em> the decision when a case is upheld, because
 *       upholding leaves it in place and nothing afterwards can say how far back to look.</li>
 * </ul>
 *
 * <p>The cost of splitting is that a failure halfway leaves work done. A case opened whose
 * suppression then failed is visible — an open case in the queue with a deadline on it — and it is
 * the safer of the two failures, since the alternative ordering cannot commit at all.
 */
@Service
public class SubjectRightsService {

    /**
     * Sent to everyone who was told the thing that turned out to be wrong.
     *
     * <p>Carries the subject id and nothing else. The recipient already has that id — it is what
     * their own inquiry returned — so repeating it discloses nothing new, and it is the only value
     * they hold that lets them find the decision they made on the strength of the old answer.
     */
    private static final String SUPERSEDED_MESSAGE_KEY = "tixInquiryResultSuperseded";

    private final SubjectRequestRepository requests;
    private final DebtRecordRepository debtRecords;
    private final SubjectResolver subjects;
    private final TenantService tenants;
    private final NotificationService notifications;
    private final AuditService audit;

    /**
     * Every unit of work here, and the reason this class has no {@code @Transactional} methods.
     *
     * <p>{@code PROPAGATION_REQUIRES_NEW}, deliberately and load-bearingly.
     * {@link ai.dival.dip.common.tenancy.TenantAwareDataSource} binds the tenant when a connection
     * is checked out, so a transaction is pinned to whichever tenant was current when it began.
     * A template with the default {@code REQUIRED} joins whatever transaction it finds — and if
     * the caller had one, every "act as tenant B" block would quietly keep tenant A's connection
     * and tenant A's binding. Suspending and starting afresh is what forces a new checkout, and a
     * new checkout is what reads {@link TenantContext} again.
     *
     * <p>Depending on the caller not being transactional would work today and break the first time
     * somebody annotates a method above this one, with no test failing.
     */
    private final TransactionTemplate perTenant;

    public SubjectRightsService(SubjectRequestRepository requests,
                                DebtRecordRepository debtRecords,
                                SubjectResolver subjects,
                                TenantService tenants,
                                NotificationService notifications,
                                AuditService audit,
                                PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.debtRecords = debtRecords;
        this.subjects = subjects;
        this.tenants = tenants;
        this.notifications = notifications;
        this.audit = audit;
        this.perTenant = new TransactionTemplate(transactionManager);
        this.perTenant.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * One unit of work, in its own transaction, as whichever tenant is currently bound.
     *
     * <p>Deliberately not overloaded with a {@code Runnable} variant. A lambda like
     * {@code () -> repository.findAll()} is both a valid {@code Supplier} and a valid
     * {@code Runnable}, so the two overloads would make every such call site an ambiguous-method
     * compile error. Work with nothing to return ends with {@code return null;} instead, which is
     * uglier in three places and unambiguous in all of them.
     */
    private <T> T unit(java.util.function.Supplier<T> work) {
        return perTenant.execute(status -> work.get());
    }

    /**
     * Opens a case for somebody who has come forward.
     *
     * <p>Looks the person up; never creates them. Somebody asking what the registry holds about
     * them must not be added to it by the act of asking, which is why this uses
     * {@link SubjectResolver#locate} rather than {@code resolve}.
     *
     * <p>A DISPUTE or RECTIFICATION suppresses the person's records immediately, before the case
     * is decided. That is deliberate: the harm of being wrongly listed accrues every day the
     * listing stands, and somebody who says "that is not my debt" should stop being reported to
     * other operators while their claim is examined rather than after. The suppression is
     * reversible and recorded; a wrongful refusal of credit is not.
     */
    public SubjectRequest raise(SubjectRequestType type, IdentifierType identifierType,
                                String identifier, String detail, UUID actorId) {
        // The case is opened and committed before anything is suppressed, and the order is forced
        // rather than chosen: tix_debt_record.suppressed_by_request_id is a foreign key, so a
        // suppression committed in its own transaction before the subject_request row exists
        // would be rejected by the database.
        //
        // The cost of that order is a window. If a later per-tenant unit fails, the case exists
        // and open with the person still listed. That is visible — an open case is in the queue
        // with a deadline on it — and it is the safer of the two failures: the alternative
        // ordering cannot happen at all.
        // The subject id is read here, inside the unit, and carried out. Reaching through
        // request.getSubject() afterwards would be a LazyInitializationException on a detached
        // entity — the standing tax on managing transactions by hand.
        CaseAndSubject opened = unit(() -> {
            Subject subject = subjects.locate(identifierType, identifier)
                    .orElseThrow(SubjectNotInRegistryException::new);
            SubjectRequest saved = requests.save(new SubjectRequest(subject, type, detail, actorId));
            audit.record("TIX_SUBJECT_REQUEST_RAISED", "SubjectRequest",
                    saved.getId().toString(), AuditService.OUTCOME_SUCCESS, actorId,
                    type + " for subject " + subject.getId());
            return new CaseAndSubject(saved, subject.getId());
        });

        if (type == SubjectRequestType.DISPUTE || type == SubjectRequestType.RECTIFICATION) {
            int suppressed = suppressAcrossOperators(
                    opened.subjectId(), opened.request().getId());
            audit.record("TIX_RECORDS_SUPPRESSED", "SubjectRequest",
                    opened.request().getId().toString(), AuditService.OUTCOME_SUCCESS, actorId,
                    suppressed + " record(s) suppressed pending the outcome");
        }
        return opened.request();
    }

    /** Single-tenant: the case belongs to whoever is asking, and nothing crosses a boundary. */
    public SubjectRequest verifyIdentity(UUID requestId, String evidence, UUID actorId) {
        return unit(() -> {
            SubjectRequest request = requireOwnRequest(requestId);
            request.verifyIdentity(actorId, evidence);
            audit.record("TIX_SUBJECT_IDENTITY_VERIFIED", "SubjectRequest", requestId.toString(),
                    AuditService.OUTCOME_SUCCESS, actorId, request.getIdentityEvidence());
            return request;
        });
    }

    /**
     * Answers an access request with everything the exchange holds about the person.
     *
     * <p>The one place an operator legitimately sees across the boundary, and it is not really
     * the operator seeing it — it is the subject, through whoever is handling their case. Heavily
     * audited for that reason: this is the single call in the system that assembles one person's
     * whole file, and if it were ever misused, the trail is what makes that visible.
     */
    public List<Disclosure> disclose(UUID requestId, UUID actorId) {
        UUID subjectId = unit(() -> {
            SubjectRequest request = requireOwnRequest(requestId);
            if (request.getRequestType() != SubjectRequestType.ACCESS) {
                throw new PolicyRefusedException("This case is not an access request.");
            }
            if (request.getStatus() != SubjectRequestStatus.IDENTITY_VERIFIED) {
                throw new PolicyRefusedException(
                        "Verify the person's identity before disclosing their file.");
            }
            if (actorId == null) {
                // The most sensitive read in the system: one person's entire file, across every
                // operator. An audit row for it that names nobody would record that it happened
                // and not who did it, which is the half that matters if it is ever misused.
                throw new PolicyRefusedException(
                        "A disclosure has to name who made it.");
            }
            return request.getSubject().getId();
        });

        List<Disclosure> disclosures = new ArrayList<>();
        for (Tenant tenant : tenants.list()) {
            // A read, and it still needs its own transaction per tenant. Row-level security
            // governs SELECT as well as INSERT: on a connection bound to the caller, an explicit
            // tenant predicate naming somebody else matches nothing at all, and the person's file
            // would come back containing only the operator whose office they walked into.
            TenantContext.runAs(tenant.getId(), () -> unit(() -> {
                debtRecords.findByTenantIdAndSubjectIdOrderByDefaultDateDesc(
                                tenant.getId(), subjectId)
                        .forEach(record -> disclosures.add(new Disclosure(
                                tenant.getName(),
                                record.getStatus(),
                                record.getAmount().toPlainString() + " " + record.getCurrency(),
                                record.getDefaultDate().toString(),
                                record.getRetentionUntil().toString())));
                return null;
            }));
        }

        audit.record("TIX_SUBJECT_FILE_DISCLOSED", "SubjectRequest", requestId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                disclosures.size() + " record(s) disclosed to the subject");
        return List.copyOf(disclosures);
    }

    /**
     * Decides an erasure request.
     *
     * <p>The rule, chosen deliberately: <strong>settled records are erased, outstanding ones are
     * not.</strong> An unconditional erasure right in a debt registry would let anybody delete
     * their own debts, and no operator would contribute to an exchange that worked that way — so
     * the right would defeat the thing it is attached to. Once a debt is regularised the operator
     * has no remaining interest in reporting it, and waiting out the retention window serves
     * nobody; that is why settling brings erasure forward here as it does elsewhere.
     *
     * <p>Partial outcomes are normal and are stated as such. Somebody with two settled debts and
     * one outstanding gets two erased and a written reason for the third.
     */
    public SubjectRequest decideErasure(UUID requestId, UUID actorId) {
        UUID subjectId = unit(() -> {
            SubjectRequest request = requireOwnRequest(requestId);
            if (request.getRequestType() != SubjectRequestType.ERASURE) {
                throw new PolicyRefusedException("This case is not an erasure request.");
            }
            return request.getSubject().getId();
        });

        int erased = 0;
        int kept = 0;
        // Captured before the rows are deleted, because it is the only way to know how far back
        // to look for institutions that were told about them. After the delete there is nothing
        // left to ask.
        Instant earliestErased = null;

        for (Tenant tenant : tenants.list()) {
            Counts counts = TenantContext.runAsResult(tenant.getId(), () ->
                    perTenant.execute(status -> {
                        List<DebtRecord> mine = debtRecords
                                .findByTenantIdAndSubjectIdOrderByDefaultDateDesc(
                                        tenant.getId(), subjectId);
                        List<DebtRecord> erasable = mine.stream()
                                .filter(record -> record.getStatus() == DebtStatus.SETTLED)
                                .toList();
                        erasable.forEach(record -> audit.record("TIX_RECORD_ERASED", "DebtRecord",
                                record.getId().toString(), AuditService.OUTCOME_SUCCESS, actorId,
                                "Erased on subject request " + requestId));
                        Instant oldest = earliestCreation(erasable).orElse(null);
                        debtRecords.deleteAll(erasable);
                        return new Counts(erasable.size(), mine.size() - erasable.size(), oldest);
                    }));
            erased += counts == null ? 0 : counts.erased();
            kept += counts == null ? 0 : counts.kept();
            Instant oldest = counts == null ? null : counts.oldestErased();
            if (oldest != null && (earliestErased == null || oldest.isBefore(earliestErased))) {
                earliestErased = oldest;
            }
        }

        // Article 214 again, and it applies to erasure in the same sentence as rectification.
        if (earliestErased != null) {
            notifyPriorRecipients(subjectId, earliestErased, actorId);
        }

        String reason = erased + " settled record(s) erased; " + kept
                + " kept because the obligation is still outstanding. "
                + "A record of an unpaid debt may be kept for its retention period; "
                + "settle it and the erasure can be granted.";

        boolean granted = erased > 0;
        SubjectRequest decided = unit(() -> {
            SubjectRequest request = requireOwnRequest(requestId);
            if (granted) {
                request.uphold(actorId, reason);
            } else {
                request.refuse(actorId, reason);
            }
            audit.record("TIX_SUBJECT_REQUEST_DECIDED", "SubjectRequest", requestId.toString(),
                    AuditService.OUTCOME_SUCCESS, actorId, request.getStatus() + ": " + reason);
            return request;
        });

        // Article 214 again, and it applies to erasure in the same sentence as rectification.
        // After the decision is committed: telling institutions a record was withdrawn and then
        // rolling the withdrawal back would be worse than not telling them.
        if (earliestErased != null) {
            notifyPriorRecipients(subjectId, earliestErased, actorId);
        }
        return decided;
    }

    /** Closes a dispute or rectification, and lifts exactly the suppression it caused. */
    public SubjectRequest close(UUID requestId, boolean upheld, String reason, UUID actorId) {
        // The suppression to be examined has to be read before the decision, because upholding
        // leaves it in place and there is no later moment that can tell how far back to look.
        Optional<Instant> affectedSince = upheld
                ? earliestSuppressedCreation(requestId)
                : Optional.empty();

        CaseAndSubject decision = unit(() -> {
            SubjectRequest request = requireOwnRequest(requestId);
            if (upheld) {
                request.uphold(actorId, reason);
            } else {
                request.refuse(actorId, reason);
            }
            audit.record("TIX_SUBJECT_REQUEST_DECIDED", "SubjectRequest", requestId.toString(),
                    AuditService.OUTCOME_SUCCESS, actorId, request.getStatus() + ": " + reason);
            return new CaseAndSubject(request, request.getSubject().getId());
        });

        if (upheld) {
            // Article 214: the correction has to reach the institutions that were told the
            // uncorrected thing. Upholding a dispute is a finding that the record was wrong, and
            // the people who acted on it are the point of the obligation.
            affectedSince.ifPresent(since ->
                    notifyPriorRecipients(decision.subjectId(), since, actorId));
        } else {
            // Refused, so the records go back to being reported. Upholding leaves them suppressed:
            // a contested claim that was found to be wrong should not quietly return to the
            // exchange because the case is closed.
            liftAcrossOperators(requestId);
        }
        return decision.request();
    }

    /**
     * The person stops pursuing their case.
     *
     * <p>WITHDRAWN existed as a status from the beginning and nothing set it, so a case somebody
     * abandoned stayed open until an operator decided it — which is a request for a decision
     * nobody wants to make, sitting on a queue with a statutory deadline it will miss.
     *
     * <p><strong>The suppression is lifted, and that is the part that matters.</strong> Raising a
     * dispute takes the records out of the exchange immediately, before anybody weighs it, because
     * the harm of being wrongly listed accrues daily. Withdrawing without lifting would make that
     * protection into a hole: raise a dispute against a record that is entirely true, walk away,
     * and it stays out of the exchange forever with no decision anybody can appeal. Same treatment
     * as a refusal, for a stronger reason — a refusal at least represents somebody's finding.
     *
     * <p>A note is required for the same reason verification evidence is. This is the one way to
     * close a case without deciding it, so an operator with a full queue and a deadline has an
     * obvious use for it. "Called on 14 May, said she had settled with the shop" is something a
     * regulator can test; a bare status change is not.
     */
    public SubjectRequest withdraw(UUID requestId, String note, UUID actorId) {
        if (note == null || note.isBlank()) {
            throw new PolicyRefusedException(
                    "Say how the person told you they were withdrawing. A case closed without a "
                            + "decision and without a record of who asked for it is indistinguishable "
                            + "from a case quietly dropped.");
        }

        SubjectRequest withdrawn = unit(() -> {
            SubjectRequest request = requireOwnRequest(requestId);
            request.withdraw();
            audit.record("TIX_SUBJECT_REQUEST_WITHDRAWN", "SubjectRequest", requestId.toString(),
                    AuditService.OUTCOME_SUCCESS, actorId, note);
            return request;
        });

        // After the status is committed, in the same order close() uses: lifting a suppression and
        // then failing to record why would put records back into the exchange with nothing
        // explaining it.
        liftAcrossOperators(requestId);
        return withdrawn;
    }

    public List<SubjectRequest> listOwn() {
        return unit(() -> requests.findByTenantIdOrderByRaisedAtDesc(TenantContext.require()));
    }

    // --- article 214: telling the people who were told ----------------------

    /**
     * Tells every institution that was given this subject's status that the answer has changed.
     *
     * <p><strong>Article 214 of the Code du numérique.</strong> A rectification or an erasure must
     * be communicated « à la personne concernée elle-même ainsi qu'aux personnes à qui les données
     * inexactes […] ont été communiquées ». Without this, a wrongful listing is corrected at the
     * source and left standing in the memory of everyone who read it — and the people who read it
     * are the ones who refused somebody a line or a loan on the strength of it. Correcting the
     * database and not the decision is the half that matters least.
     *
     * <p>The recipients come from the audit trail, which is the only place the platform remembers
     * who was told what. That makes the trail load-bearing in a way it was not before: it used to
     * be evidence, read when somebody asked a question, and a gap in it is now a person who never
     * learns the record was wrong.
     *
     * <p><strong>The message carries the subject id and nothing else.</strong> Not the amount, not
     * which operator was wrong, not what the correction was — an obligation to notify is not a
     * licence to disclose, and the recipient can already look the subject up again if they want
     * the current answer. The id is one they already hold, because their own inquiry returned it.
     *
     * <p>Bounded by {@code since} rather than sweeping all history: only the inquiries made while
     * the affected records existed were told the wrong thing. Somebody who enquired before the
     * record was ever declared was told the truth at the time and should not receive a correction
     * to an answer that was right.
     *
     * @param since the earliest moment an affected record existed
     * @return how many institutions were told
     */
    private int notifyPriorRecipients(UUID subjectId, Instant since, UUID actorId) {
        String subjectRef = subjectId.toString();
        int told = 0;

        for (Tenant tenant : tenants.list()) {
            // Per tenant, as everywhere else here: audit rows and notifications are both
            // tenant-owned, and the policy's WITH CHECK clause forbids writing another
            // operator's rows however good the reason.
            Integer count = TenantContext.runAsResult(tenant.getId(), () ->
                    perTenant.execute(status -> {
                        List<UUID> enquirers = audit.actorsServed(
                                ExchangeService.INQUIRY_ACTION, "Subject", subjectRef, since);
                        enquirers.forEach(enquirer -> notifications.notify(
                                enquirer,
                                SUPERSEDED_MESSAGE_KEY,
                                Map.of("subject", subjectRef),
                                // Somebody may have declined an application on this. It is not an
                                // FYI.
                                Notification.Severity.CRITICAL,
                                "Subject",
                                subjectRef));
                        return enquirers.size();
                    }));
            told += count == null ? 0 : count;
        }

        audit.record("TIX_PRIOR_RECIPIENTS_NOTIFIED", "Subject", subjectRef,
                AuditService.OUTCOME_SUCCESS, actorId,
                told + " prior recipient(s) told the result they were given is superseded");
        return told;
    }

    /**
     * The moment the earliest of these records came into existence.
     *
     * <p>Empty when there are no records, in which case nobody was ever told anything about them
     * and there is nobody to notify.
     */
    private static Optional<Instant> earliestCreation(List<DebtRecord> records) {
        return records.stream().map(DebtRecord::getCreatedAt).min(Instant::compareTo);
    }

    /** When the oldest record suppressed by this case was declared, across every operator. */
    private Optional<Instant> earliestSuppressedCreation(UUID requestId) {
        Instant earliest = null;
        for (Tenant tenant : tenants.list()) {
            // In its own transaction like every other per-tenant block, and for the same reason:
            // a read on a connection bound to somebody else returns nothing under RLS.
            Optional<Instant> mine = TenantContext.runAsResult(tenant.getId(), () ->
                    unit(() -> earliestCreation(debtRecords.findByTenantIdAndSuppressedByRequestId(
                            tenant.getId(), requestId))));
            if (mine != null && mine.isPresent()
                    && (earliest == null || mine.get().isBefore(earliest))) {
                earliest = mine.get();
            }
        }
        return Optional.ofNullable(earliest);
    }

    // --- cross-operator effects --------------------------------------------

    private int suppressAcrossOperators(UUID subjectId, UUID requestId) {
        int total = 0;
        for (Tenant tenant : tenants.list()) {
            Integer count = TenantContext.runAsResult(tenant.getId(), () ->
                    perTenant.execute(status -> {
                        List<DebtRecord> mine = debtRecords
                                .findByTenantIdAndSubjectIdOrderByDefaultDateDesc(
                                        tenant.getId(), subjectId).stream()
                                .filter(record -> record.getStatus() == DebtStatus.OUTSTANDING)
                                .toList();
                        mine.forEach(record -> record.suppressFor(requestId));
                        return mine.size();
                    }));
            total += count == null ? 0 : count;
        }
        return total;
    }

    private void liftAcrossOperators(UUID requestId) {
        for (Tenant tenant : tenants.list()) {
            TenantContext.runAs(tenant.getId(), () ->
                    perTenant.executeWithoutResult(status ->
                            debtRecords.findByTenantIdAndSuppressedByRequestId(
                                            tenant.getId(), requestId)
                                    .forEach(DebtRecord::liftSuppression)));
        }
    }

    private SubjectRequest requireOwnRequest(UUID requestId) {
        return requests.findByIdAndTenantId(requestId, TenantContext.require())
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    private record Counts(int erased, int kept, Instant oldestErased) {
    }

    /**
     * A case, plus the subject id read while the entity was still managed.
     *
     * <p>The second field exists only because the first is detached by the time the caller uses
     * it. Reaching through {@code request.getSubject()} afterwards is a
     * {@code LazyInitializationException} — the standing tax on managing transactions by hand,
     * and cheaper than what it buys.
     */
    private record CaseAndSubject(SubjectRequest request, UUID subjectId) {
    }

    /**
     * One operator's entry in a person's file.
     *
     * @param operator the operator's name — the subject is entitled to know who is reporting them,
     *                 which is precisely what an enquiring operator is never told
     */
    public record Disclosure(String operator, DebtStatus status, String amount,
                             String defaultDate, String retainedUntil) {
    }

    /**
     * Nobody by that identifier is in the registry.
     *
     * <p>Says so plainly rather than hiding behind a generic refusal. Everywhere else the exchange
     * refuses to confirm whether a record exists, because the asker is an operator who might be
     * fishing. Here the asker is the person themselves, and "you are not in this registry" is the
     * answer they came for.
     */
    public static class SubjectNotInRegistryException extends ResourceNotFoundException {
        public SubjectNotInRegistryException() {
            super("No subject in the exchange holds that identifier.");
        }
    }

    public static class RequestNotFoundException extends ResourceNotFoundException {
        public RequestNotFoundException(UUID id) {
            super("Subject request not found: " + id);
        }
    }
}
