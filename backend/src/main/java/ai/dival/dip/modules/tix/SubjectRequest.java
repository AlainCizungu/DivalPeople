package ai.dival.dip.modules.tix;

import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Somebody in the registry, asking about themselves.
 *
 * <p>The order of operations is the whole design: opened, then identity verified with evidence
 * recorded, then decided with grounds recorded. Each step is enforced here and again by a check
 * constraint in V21, because upholding a case suppresses or erases records — and doing that on an
 * unverified assertion would make "I am that person" enough to silence a debt.
 *
 * <p>Note what a decision cannot be: silent. A refusal without a reason is the outcome nobody can
 * appeal, and the ability to appeal is most of what a right is.
 */
@Entity
@Table(name = "subject_request")
public class SubjectRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false, updatable = false)
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, updatable = false, length = 20)
    private SubjectRequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubjectRequestStatus status;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "raised_by")
    private UUID raisedBy;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    @Column(name = "identity_verified_by")
    private UUID identityVerifiedBy;

    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    @Column(name = "identity_evidence", length = 500)
    private String identityEvidence;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SubjectRequest() {
        // for JPA
    }

    public SubjectRequest(Subject subject, SubjectRequestType requestType, String detail,
                          UUID raisedBy) {
        this.tenantId = TenantContext.require();
        this.subject = subject;
        this.requestType = requestType;
        this.detail = detail;
        this.raisedBy = raisedBy;
        this.status = SubjectRequestStatus.RECEIVED;
        this.raisedAt = Instant.now();
    }

    /**
     * Records that somebody checked this is the right person, and how.
     *
     * <p>The evidence is free text and mandatory. "National ID CD-1234-5678 seen in person,
     * photograph matches" is something a regulator can assess; a checkbox is not, and a dropdown
     * of three options would turn a judgement into a formality.
     */
    public void verifyIdentity(UUID verifier, String evidence) {
        if (status != SubjectRequestStatus.RECEIVED) {
            throw new PolicyRefusedException(
                    "Identity can only be verified on a case that is still RECEIVED; this one is "
                            + status + ".");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new PolicyRefusedException(
                    "Say how the person's identity was checked. A verification nobody can assess "
                            + "afterwards is not a verification.");
        }
        this.identityVerifiedBy = verifier;
        this.identityVerifiedAt = Instant.now();
        this.identityEvidence = evidence.trim();
        this.status = SubjectRequestStatus.IDENTITY_VERIFIED;
    }

    public void uphold(UUID decider, String reason) {
        decide(SubjectRequestStatus.UPHELD, decider, reason);
    }

    public void refuse(UUID decider, String reason) {
        decide(SubjectRequestStatus.REFUSED, decider, reason);
    }

    public void withdraw() {
        if (isDecided()) {
            throw new PolicyRefusedException("A decided case cannot be withdrawn.");
        }
        this.status = SubjectRequestStatus.WITHDRAWN;
    }

    private void decide(SubjectRequestStatus outcome, UUID decider, String reason) {
        if (status != SubjectRequestStatus.IDENTITY_VERIFIED) {
            throw new PolicyRefusedException(
                    "A case cannot be decided before the person's identity has been verified. "
                            + "This one is " + status + ".");
        }
        if (reason == null || reason.isBlank()) {
            throw new PolicyRefusedException(
                    "A decision needs grounds. Whoever receives this answer has to be able to "
                            + "challenge it, and they cannot challenge silence.");
        }
        this.status = outcome;
        this.decidedBy = decider;
        this.decidedAt = Instant.now();
        this.decisionReason = reason.trim();
    }

    public boolean isDecided() {
        return status == SubjectRequestStatus.UPHELD || status == SubjectRequestStatus.REFUSED;
    }

    /** Open cases are the ones whose suppression of records is still in force. */
    public boolean isOpen() {
        return status == SubjectRequestStatus.RECEIVED
                || status == SubjectRequestStatus.IDENTITY_VERIFIED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Subject getSubject() {
        return subject;
    }

    public SubjectRequestType getRequestType() {
        return requestType;
    }

    public SubjectRequestStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public UUID getRaisedBy() {
        return raisedBy;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public UUID getIdentityVerifiedBy() {
        return identityVerifiedBy;
    }

    public Instant getIdentityVerifiedAt() {
        return identityVerifiedAt;
    }

    public String getIdentityEvidence() {
        return identityEvidence;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }
}
