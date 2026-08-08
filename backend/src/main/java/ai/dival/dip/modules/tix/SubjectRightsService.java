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
import org.springframework.transaction.annotation.Transactional;
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
 * <p><strong>KNOWN DEFECT, found August 2026 and not yet fixed. Read this before trusting the
 * per-tenant loops below.</strong> {@code TenantAwareDataSource} binds the tenant when a
 * connection is checked out, so a transaction is pinned to whichever tenant was current when it
 * began. Every public method here is {@code @Transactional}, which means the
 * {@code transactionTemplate.execute} calls inside the loops join the caller's transaction rather
 * than starting their own — and therefore keep the caller's connection, and the caller's tenant
 * binding. Under row-level security as the unprivileged {@code dip_app} role, the cross-operator
 * suppression and erasure would silently affect only the calling operator's rows.
 *
 * <p>{@link RetentionPurge} and the scheduled scanners have the same shape and are correct,
 * because nothing wraps them in an outer transaction; the template genuinely starts a new one each
 * time. This class is the exception.
 *
 * <p>The integration tests cannot see it: they connect as the schema owner, which bypasses
 * row-level security entirely, so the writes succeed regardless of which tenant the connection is
 * bound to. It would show in {@code RowLevelSecurityTest}, which connects as {@code dip_app}, and
 * there is no case there for this path.
 *
 * <p>The fix is not a one-line propagation change. {@code REQUIRES_NEW} on the suppression loop in
 * {@link #raise} would commit the suppression before the {@code subject_request} row it points at,
 * and V21's foreign key would reject it. Doing this properly means committing the case first and
 * applying effects afterwards, as separate units — which is what this class's documentation always
 * claimed it did.
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
    private final TransactionTemplate transactionTemplate;

    public SubjectRightsService(SubjectRequestRepository requests,
                                DebtRecordRepository debtRecords,
                                SubjectResolver subjects,
                                TenantService tenants,
                                NotificationService notifications,
                                AuditService audit,
                                TransactionTemplate transactionTemplate) {
        this.requests = requests;
        this.debtRecords = debtRecords;
        this.subjects = subjects;
        this.tenants = tenants;
        this.notifications = notifications;
        this.audit = audit;
        this.transactionTemplate = transactionTemplate;
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
    @Transactional
    public SubjectRequest raise(SubjectRequestType type, IdentifierType identifierType,
                                String identifier, String detail, UUID actorId) {
        Subject subject = subjects.locate(identifierType, identifier)
                .orElseThrow(SubjectNotInRegistryException::new);

        SubjectRequest request = requests.save(
                new SubjectRequest(subject, type, detail, actorId));

        audit.record("TIX_SUBJECT_REQUEST_RAISED", "SubjectRequest",
                request.getId().toString(), AuditService.OUTCOME_SUCCESS, actorId,
                type + " for subject " + subject.getId());

        if (type == SubjectRequestType.DISPUTE || type == SubjectRequestType.RECTIFICATION) {
            int suppressed = suppressAcrossOperators(subject.getId(), request.getId());
            audit.record("TIX_RECORDS_SUPPRESSED", "SubjectRequest",
                    request.getId().toString(), AuditService.OUTCOME_SUCCESS, actorId,
                    suppressed + " record(s) suppressed pending the outcome");
        }
        return request;
    }

    @Transactional
    public SubjectRequest verifyIdentity(UUID requestId, String evidence, UUID actorId) {
        SubjectRequest request = requireOwnRequest(requestId);
        request.verifyIdentity(actorId, evidence);
        audit.record("TIX_SUBJECT_IDENTITY_VERIFIED", "SubjectRequest", requestId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, request.getIdentityEvidence());
        return request;
    }

    /**
     * Answers an access request with everything the exchange holds about the person.
     *
     * <p>The one place an operator legitimately sees across the boundary, and it is not really
     * the operator seeing it — it is the subject, through whoever is handling their case. Heavily
     * audited for that reason: this is the single call in the system that assembles one person's
     * whole file, and if it were ever misused, the trail is what makes that visible.
     */
    @Transactional(readOnly = true)
    public List<Disclosure> disclose(UUID requestId, UUID actorId) {
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
            // operator. An audit row for it that names nobody would record that it happened and
            // not who did it, which is the half that matters if it is ever misused.
            throw new PolicyRefusedException(
                    "A disclosure has to name who made it.");
        }

        List<Disclosure> disclosures = new ArrayList<>();
        for (Tenant tenant : tenants.list()) {
            TenantContext.runAs(tenant.getId(), () ->
                    debtRecords.findByTenantIdAndSubjectIdOrderByDefaultDateDesc(
                                    tenant.getId(), request.getSubject().getId())
                            .forEach(record -> disclosures.add(new Disclosure(
                                    tenant.getName(),
                                    record.getStatus(),
                                    record.getAmount().toPlainString() + " " + record.getCurrency(),
                                    record.getDefaultDate().toString(),
                                    record.getRetentionUntil().toString()))));
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
    @Transactional
    public SubjectRequest decideErasure(UUID requestId, UUID actorId) {
        SubjectRequest request = requireOwnRequest(requestId);
        if (request.getRequestType() != SubjectRequestType.ERASURE) {
            throw new PolicyRefusedException("This case is not an erasure request.");
        }

        UUID subjectId = request.getSubject().getId();
        int erased = 0;
        int kept = 0;
        // Captured before the rows are deleted, because it is the only way to know how far back
        // to look for institutions that were told about them. After the delete there is nothing
        // left to ask.
        Instant earliestErased = null;

        for (Tenant tenant : tenants.list()) {
            Counts counts = TenantContext.runAsResult(tenant.getId(), () ->
                    transactionTemplate.execute(status -> {
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

        if (erased > 0) {
            request.uphold(actorId, reason);
        } else {
            request.refuse(actorId, reason);
        }
        audit.record("TIX_SUBJECT_REQUEST_DECIDED", "SubjectRequest", requestId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, request.getStatus() + ": " + reason);
        return request;
    }

    /** Closes a dispute or rectification, and lifts exactly the suppression it caused. */
    @Transactional
    public SubjectRequest close(UUID requestId, boolean upheld, String reason, UUID actorId) {
        SubjectRequest request = requireOwnRequest(requestId);

        if (upheld) {
            request.uphold(actorId, reason);
            // Article 214: the correction has to reach the institutions that were told the
            // uncorrected thing. Upholding a dispute is a finding that the record was wrong, and
            // the people who acted on it are the point of the obligation.
            earliestSuppressedCreation(requestId).ifPresent(since ->
                    notifyPriorRecipients(request.getSubject().getId(), since, actorId));
        } else {
            request.refuse(actorId, reason);
            // Refused, so the records go back to being reported. Upholding leaves them suppressed:
            // a contested claim that was found to be wrong should not quietly return to the
            // exchange because the case is closed.
            liftAcrossOperators(requestId);
        }

        audit.record("TIX_SUBJECT_REQUEST_DECIDED", "SubjectRequest", requestId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, request.getStatus() + ": " + reason);
        return request;
    }

    @Transactional(readOnly = true)
    public List<SubjectRequest> listOwn() {
        return requests.findByTenantIdOrderByRaisedAtDesc(TenantContext.require());
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
                    transactionTemplate.execute(status -> {
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
            Optional<Instant> mine = TenantContext.runAsResult(tenant.getId(), () ->
                    earliestCreation(debtRecords.findByTenantIdAndSuppressedByRequestId(
                            tenant.getId(), requestId)));
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
                    transactionTemplate.execute(status -> {
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
                    transactionTemplate.executeWithoutResult(status ->
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
