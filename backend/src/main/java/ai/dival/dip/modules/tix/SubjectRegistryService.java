package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The registry's own view of who is in it, and the one operation that changes that.
 *
 * <p>Everything else in {@code tix} answers for one operator. This answers for the registry:
 * subjects are shared, several operators hold records against the same person, and deciding that
 * two of those people are one person is not any operator's decision to make. Its only caller is
 * the resolution module, whose controller requires {@code PLATFORM_ADMIN}.
 *
 * <p>The reason it exists rather than the resolution module reading subjects directly: a module
 * reaches another through its service. Resolution has no business knowing what an RCCM is, how
 * identifiers are scoped, or that a debt record carries a subject at all.
 */
@Service
public class SubjectRegistryService {

    private final SubjectRepository subjects;
    private final DebtRecordRepository debtRecords;
    private final SubjectIdentifierRepository identifiers;
    private final TenantService tenants;
    private final AuditService audit;

    /**
     * One tenant's share of a merge, in its own transaction.
     *
     * <p>The same device the retention purge and the dispute lift use, and for the same reason:
     * row-level security scopes writes to the current tenant, so moving four operators' records
     * onto one subject is four transactions rather than one privileged sweep. Slower, and it
     * leaves the boundary intact — the alternative is a policy permitting cross-tenant writes,
     * which would then exist for anything else that wanted it.
     */
    private final TransactionTemplate perTenant;

    public SubjectRegistryService(SubjectRepository subjects, DebtRecordRepository debtRecords,
                                  SubjectIdentifierRepository identifiers, TenantService tenants,
                                  AuditService audit,
                                  PlatformTransactionManager transactionManager) {
        this.subjects = subjects;
        this.debtRecords = debtRecords;
        this.identifiers = identifiers;
        this.tenants = tenants;
        this.audit = audit;
        this.perTenant = new TransactionTemplate(transactionManager);
        this.perTenant.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Every subject still answering for itself, with what a comparison can use.
     *
     * <p>Merged subjects are excluded: they have already been decided and pairing them again would
     * put a settled question back in the queue.
     *
     * <p>Loads the registry into memory, which is honest about where this stands. At the scale of
     * two operator books it is a few thousand rows and the scan takes a moment; at national scale
     * it is the wrong shape and the fix is a blocking key computed in the database. Said here
     * rather than discovered later.
     */
    @Transactional(readOnly = true)
    public List<RegistrySubject> snapshot() {
        List<RegistrySubject> found = new ArrayList<>();
        for (Subject subject : subjects.findAll()) {
            if (subject.isMerged()) {
                continue;
            }
            found.add(snapshotOf(subject));
        }
        return List.copyOf(found);
    }

    /** One subject as the registry holds it, for a screen that has to show a reviewer both. */
    @Transactional(readOnly = true)
    public RegistrySubject describe(UUID subjectId) {
        return snapshotOf(subjects.findById(subjectId)
                .orElseThrow(() -> new SubjectNotFoundException(subjectId)));
    }

    /**
     * Files two subjects as one.
     *
     * <p>The survivor is the older row, chosen by the registry rather than by whoever is looking:
     * it is the one other operators are most likely to have already resolved against, and letting
     * a reviewer pick would make the outcome depend on which record happened to be on the left of
     * the screen.
     *
     * <p>Refuses a merge where either side is already merged. Chaining is survivable — the pointer
     * is followed — but a reviewer confirming against a subject that stopped being an answer
     * yesterday is deciding on a screen that is out of date, and should be told so.
     *
     * @return how much moved, which the caller records and shows
     */
    @Transactional
    public Merge merge(UUID firstId, UUID secondId, UUID actorId) {
        Subject first = subjects.findById(firstId)
                .orElseThrow(() -> new SubjectNotFoundException(firstId));
        Subject second = subjects.findById(secondId)
                .orElseThrow(() -> new SubjectNotFoundException(secondId));
        if (first.isMerged() || second.isMerged()) {
            throw new PolicyRefusedException(
                    "One of these subjects has already been merged into another. Rescan before "
                            + "deciding, so the case shows where the records actually are.");
        }

        boolean firstIsOlder = !first.getCreatedAt().isAfter(second.getCreatedAt());
        Subject survivor = firstIsOlder ? first : second;
        Subject absorbed = firstIsOlder ? second : first;

        // Ids rather than entities across the boundary below. Each per-tenant unit runs in its own
        // transaction and therefore its own persistence context; handing it an entity loaded out
        // here would have it assigning a detached instance, which Hibernate refuses at flush time
        // — a long way from the line that caused it.
        UUID survivorId = survivor.getId();
        UUID absorbedId = absorbed.getId();

        int recordsMoved = 0;
        int identifiersMoved = 0;

        for (Tenant tenant : tenants.list()) {
            Integer moved = TenantContext.runAsResult(tenant.getId(), () ->
                    perTenant.execute(status -> {
                        Subject target = subjects.getReferenceById(survivorId);
                        List<DebtRecord> theirs = debtRecords
                                .findByTenantIdAndSubjectIdOrderByDefaultDateDesc(
                                        tenant.getId(), absorbedId);
                        for (DebtRecord record : theirs) {
                            record.reassignTo(target);
                            audit.record("TIX_RECORD_REASSIGNED", "DebtRecord",
                                    record.getId().toString(), AuditService.OUTCOME_SUCCESS,
                                    actorId, "Identity merge into subject " + survivorId);
                        }
                        // Account references are this operator's own numbering and only writable
                        // in its own context, which is the other half of why this loop exists.
                        int scoped = 0;
                        for (SubjectIdentifier identifier
                                : identifiers.findBySubjectIdAndOwnerTenantId(
                                        absorbedId, tenant.getId())) {
                            identifier.reassignTo(target);
                            scoped++;
                        }
                        return theirs.size() + scoped;
                    }));
            recordsMoved += moved == null ? 0 : moved;
        }

        // National documents belong to no operator, so they move outside any tenant's boundary.
        for (SubjectIdentifier identifier
                : identifiers.findBySubjectIdAndOwnerTenantIdIsNull(absorbedId)) {
            identifier.reassignTo(survivor);
            identifiersMoved++;
        }

        absorbed.mergeInto(survivor);
        audit.record("TIX_SUBJECT_MERGED", "Subject", absorbedId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                "Merged into " + survivorId + "; " + recordsMoved
                        + " record(s) and identifier(s) moved");

        return new Merge(survivorId, absorbedId, recordsMoved, identifiersMoved);
    }

    private RegistrySubject snapshotOf(Subject subject) {
        Map<String, String> national = new LinkedHashMap<>();
        boolean hasAccountReference = false;
        for (SubjectIdentifier identifier : subject.getIdentifiers()) {
            IdentifierType type = identifier.getIdentifierType();
            if (type == IdentifierType.ACCOUNT_REFERENCE) {
                hasAccountReference = true;
            } else if (type.isStrong() && !type.isOperatorScoped()) {
                national.put(type.name(), identifier.getNormalizedValue());
            }
        }
        return new RegistrySubject(subject.getId(),
                subject.getSubjectType() == Subject.SubjectType.BUSINESS,
                subject.getFullName(), subject.getNormalizedName(), subject.getNationality(),
                subject.getDateOfBirth(), Map.copyOf(national), hasAccountReference);
    }

    /**
     * @param nationalIdentifiers documents an authority issued, keyed by type. Never an account
     *                            reference and never a phone number
     * @param hasAccountReference whether some institution numbers this subject itself, which is
     *                            worth showing a reviewer and worth nothing as evidence
     */
    public record RegistrySubject(UUID id, boolean business, String fullName,
                                  String normalizedName, String nationality,
                                  LocalDate dateOfBirth, Map<String, String> nationalIdentifiers,
                                  boolean hasAccountReference) {
    }

    /** @param survivor the older of the two, chosen by the registry rather than by the reviewer */
    public record Merge(UUID survivor, UUID absorbed, int recordsMoved, int identifiersMoved) {
    }

    /** Deliberately says only that no such subject is in the registry. */
    public static class SubjectNotFoundException extends ResourceNotFoundException {
        public SubjectNotFoundException(UUID id) {
            super("Subject not found: " + id);
        }
    }
}
