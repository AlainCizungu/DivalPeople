package ai.dival.dip.modules.tix;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the documents an operator submits into the one subject they refer to, creating it if the
 * exchange has never seen the person before.
 *
 * <p>Separate from {@link IdentityMatcher} on purpose, because they answer opposite questions.
 * The matcher is used on the <em>read</em> path and is allowed to be uncertain: it scores a
 * probable match and the caller lives with a confidence judgement. This runs on the <em>write</em>
 * path, where uncertainty is not acceptable. Attaching a debt to the wrong person is the single
 * worst thing this system can do — worse than losing the record entirely, because the harm lands
 * on somebody who has no relationship with the operator and no idea they are in a registry. So
 * resolution here is exact-match only, on normalised identifier values, and anything ambiguous is
 * refused rather than guessed.
 */
@Component
public class SubjectResolver {

    private final SubjectRepository subjects;
    private final SubjectIdentifierRepository identifiers;

    public SubjectResolver(SubjectRepository subjects, SubjectIdentifierRepository identifiers) {
        this.subjects = subjects;
        this.identifiers = identifiers;
    }

    /**
     * Resolves the subject a declaration is about.
     *
     * <p>Runs in the caller's transaction — a subject created here must roll back with the
     * declaration that created it, or a refused declaration would leave a person in the registry
     * with nothing recorded against them.
     *
     * @throws AmbiguousSubjectException when the submitted documents already belong to more than
     *         one distinct subject
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Resolution resolve(DeclarationRequest request) {
        // Kept in submission order so the exception message names documents in the order the
        // operator sent them, which is the order they can look them up in.
        Map<IdentifierType, String> submitted = new LinkedHashMap<>();
        for (DeclarationRequest.SubmittedIdentifier identifier : request.identifiers()) {
            submitted.put(identifier.type(), SubjectIdentifier.normalizeValue(identifier.value()));
        }

        Set<UUID> matchedSubjectIds = new LinkedHashSet<>();
        Map<IdentifierType, String> unknown = new LinkedHashMap<>();

        // The operator doing the declaring, which is part of the identity of anything it
        // numbered itself. Read once: a declaration is one operator's statement throughout.
        UUID declaring = TenantContext.require();

        submitted.forEach((type, value) -> {
            Optional<SubjectIdentifier> existing = identifiers.locate(type, value, declaring);
            if (existing.isPresent()) {
                matchedSubjectIds.add(existing.get().getSubject().getId());
            } else {
                unknown.put(type, value);
            }
        });

        if (matchedSubjectIds.size() > 1) {
            // Two people in the registry already hold documents from this one declaration. Either
            // the operator's records are wrong, or a document has been reused across identities,
            // or two records that should be one person were created separately. Merging subjects
            // is irreversible and would silently move one person's debts onto another, so it is
            // not something to do automatically at three in the morning on a POST.
            throw new AmbiguousSubjectException(matchedSubjectIds.size());
        }

        if (matchedSubjectIds.isEmpty()) {
            Subject created = new Subject(request.subjectTypeOrDefault(), request.fullName(),
                    request.dateOfBirth(), request.nationality());
            learnProfile(created, request);
            unknown.forEach((type, value) ->
                    created.addIdentifier(newIdentifier(type, value, declaring)));
            return new Resolution(subjects.save(created), true, unknown.size());
        }

        Subject known = subjects.findById(matchedSubjectIds.iterator().next())
                .orElseThrow(() -> new IllegalStateException(
                        "Identifier pointed at a subject that does not exist"));

        // Documents the exchange had not seen are attached to the person they were submitted
        // with. This is how the registry becomes able to match on a passport it first learned
        // from one operator when a different operator later asks by national ID.
        //
        // Known limitation, recorded rather than hidden: this trusts the declaring operator's
        // assertion that these documents belong to the same person, and that assertion then
        // affects every other operator's matches. Nothing here verifies it and nothing records
        // who made it. Provenance per identifier is the fix and it is not built.
        unknown.forEach((type, value) ->
                known.addIdentifier(newIdentifier(type, value, declaring)));

        // The case this actually matters in. One operator declares by RCCM and knows nothing but
        // the name; a second declares the same company from a file prepared against the published
        // template and supplies the sector and the address. The registry gains both without either
        // operator learning that the other is there.
        learnProfile(known, request);

        return new Resolution(known, false, unknown.size());
    }

    /**
     * Finds an existing subject without creating one.
     *
     * <p>Separate from {@link #resolve} because the difference matters: resolving is what a
     * declaration does, and creating a person who was not in the registry is a legitimate part of
     * that. Looking somebody up because they have come forward to ask about themselves must never
     * put them in the registry as a side effect of asking.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Subject> locate(IdentifierType type, String value) {
        return identifiers
                .locate(type, SubjectIdentifier.normalizeValue(value), TenantContext.require())
                .map(SubjectIdentifier::getSubject);
    }

    /**
     * Attaches the issuing operator to an identifier that has one, and to no other.
     *
     * <p>One line, in one place, because the alternative is every call site deciding — and a call
     * site that passes the tenant where it should pass null buries a national document inside a
     * single operator, where no other operator can ever match it. The constructor refuses both
     * mistakes; this is what stops them being made.
     */
    /**
     * Fills whatever the registry does not yet hold, and overwrites nothing.
     *
     * <p>A subject is registry-wide: two operators declaring one company by national document land
     * on one row, and nothing here arbitrates between "Transport et logistique" and "Logistique".
     * Last-writer-wins would let one participant rewrite another's view of a company it cannot see,
     * which is the disclosure the exchange exists to prevent, running backwards.
     *
     * <p>The cost is a stale value outliving a fresher one. That is the trade, and the way a
     * company corrects what is held about it is the subject rights path, which has a person on it.
     */
    private static void learnProfile(Subject subject, DeclarationRequest request) {
        DeclarationRequest.Profile profile = request.profile();
        if (profile != null) {
            subject.learnProfile(profile.sector(), profile.city(), profile.streetAddress());
        }
    }

    private static SubjectIdentifier newIdentifier(
            IdentifierType type, String value, UUID declaringTenantId) {
        return new SubjectIdentifier(
                type, value, type.isOperatorScoped() ? declaringTenantId : null);
    }

    /**
     * @param subject            the person the declaration is about
     * @param created            true when the exchange had never seen them before
     * @param identifiersLearned how many documents were new to the exchange
     */
    public record Resolution(Subject subject, boolean created, int identifiersLearned) {
    }

    /** Refused rather than merged. The message names no document and no subject. */
    public static class AmbiguousSubjectException extends ConflictException {
        public AmbiguousSubjectException(int distinctSubjects) {
            super("The submitted documents already belong to " + distinctSubjects
                    + " different subjects in the exchange. They cannot be merged automatically; "
                    + "declare with a single unambiguous identifier, or raise a data-quality case.");
        }
    }
}
