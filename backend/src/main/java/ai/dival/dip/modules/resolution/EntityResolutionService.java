package ai.dival.dip.modules.resolution;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.modules.tix.SubjectRegistryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finding pairs of records that may be one subject, and recording what a person decided.
 *
 * <p>The registry's work, not an operator's. A candidate spans two operators by construction —
 * the case worth reviewing is one telecom's record and another's being the same man — and showing
 * either of them the other's customer would hand a participant a rival's file. That is the
 * disclosure the whole exchange exists to prevent, so the resolution happens here, under
 * {@code PLATFORM_ADMIN}, which is also how a credit bureau actually works: the bureau holds and
 * resolves, and participants get the benefit as a better match rather than as somebody's data.
 *
 * <p><strong>Nothing merges without a person.</strong> There is no confidence at which this class
 * acts on its own, and the reason is the cost of being wrong in the direction it would fail:
 * merging moves one company's defaults onto another's file, across institutions that cannot see
 * each other, and the person who pays for it is refused credit for a debt that is not theirs.
 */
@Service
public class EntityResolutionService {

    /**
     * How the scan avoids comparing every subject against every other.
     *
     * <p>Four characters of the normalised name. Two records only meet if they agree on that much,
     * which turns a few thousand subjects squared into a few thousand small groups. It is a
     * blocking key and it has the weakness every blocking key has: two spellings that differ in
     * the first four characters never meet, so "Kabamba" and "Cabamba" are invisible to each other.
     * Stated rather than hidden — the fix is a phonetic key, and it is not built.
     */
    private static final int BLOCK_LENGTH = 4;

    /** A scan that would open ten thousand cases has not helped anybody. */
    private static final int MAX_NEW_CASES_PER_SCAN = 500;

    private final MatchCandidateRepository candidates;
    private final SubjectRegistryService registry;
    private final MatchScorer scorer;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public EntityResolutionService(MatchCandidateRepository candidates,
                                   SubjectRegistryService registry, MatchScorer scorer,
                                   AuditService audit, ObjectMapper json, Clock clock) {
        this.candidates = candidates;
        this.registry = registry;
        this.scorer = scorer;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Looks for pairs worth a person's time.
     *
     * <p>Idempotent by design: a pair that already has an open case is skipped rather than opened
     * again, so running this twice in a row is safe and running it nightly is the intent. Pairs
     * already decided are skipped too — a rejection is an answer, and a scan that kept
     * resurrecting rejected pairs would train a reviewer to stop reading the queue.
     */
    @Transactional
    public Scan scan(UUID actorId) {
        List<SubjectRegistryService.RegistrySubject> subjects = registry.snapshot();

        Map<String, List<SubjectRegistryService.RegistrySubject>> blocks = new LinkedHashMap<>();
        for (SubjectRegistryService.RegistrySubject subject : subjects) {
            blocks.computeIfAbsent(blockKey(subject.normalizedName()), key -> new ArrayList<>())
                    .add(subject);
        }

        int compared = 0;
        int opened = 0;
        Instant now = clock.instant();

        for (List<SubjectRegistryService.RegistrySubject> block : blocks.values()) {
            for (int i = 0; i < block.size(); i++) {
                for (int j = i + 1; j < block.size(); j++) {
                    if (opened >= MAX_NEW_CASES_PER_SCAN) {
                        break;
                    }
                    compared++;
                    SubjectRegistryService.RegistrySubject left = block.get(i);
                    SubjectRegistryService.RegistrySubject right = block.get(j);

                    MatchAssessment assessment = scorer.compare(facts(left), facts(right));
                    if (!assessment.worthReviewing()) {
                        continue;
                    }
                    if (alreadyKnown(left.id(), right.id())) {
                        continue;
                    }
                    candidates.save(new MatchCandidate(left.id(), right.id(),
                            assessment.confidence(), asJson(assessment.signals()),
                            MODEL_VERSION, now));
                    opened++;
                }
            }
        }

        audit.record("RESOLUTION_SCAN", "MatchCandidate", null, AuditService.OUTCOME_SUCCESS,
                actorId, "Compared " + compared + " pair(s); opened " + opened + " case(s)");
        return new Scan(subjects.size(), compared, opened);
    }

    /** The queue, least certain last. */
    @Transactional(readOnly = true)
    public List<Case> open(int limit) {
        return candidates.findByStatusOrderByConfidenceDesc(MatchStatus.OPEN, Limit.of(limit))
                .stream()
                .map(this::describe)
                .toList();
    }

    @Transactional(readOnly = true)
    public Case get(UUID caseId) {
        return describe(candidates.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId)));
    }

    /**
     * Records what somebody decided, and for a confirmation, acts on it.
     *
     * <p>A note is required on every outcome including a confirmation. The merge itself is
     * recoverable — the absorbed subject survives as a pointer — but the reason somebody believed
     * two people were one is not recoverable from anywhere else, and it is the first thing anyone
     * asks when the merge turns out to be wrong.
     */
    @Transactional
    public Decision decide(UUID caseId, MatchStatus outcome, String note, UUID actorId) {
        if (actorId == null) {
            throw new PolicyRefusedException(
                    "A decision has to name who made it. Merging two people's records is not an "
                            + "act the platform performs anonymously.");
        }
        if (note == null || note.isBlank()) {
            throw new PolicyRefusedException(
                    "Say what you saw. The reason two records were judged to be one person is the "
                            + "first thing anybody asks if it turns out they were not.");
        }

        MatchCandidate candidate = candidates.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        SubjectRegistryService.Merge merge = null;
        if (outcome == MatchStatus.CONFIRMED) {
            // Merged before the decision is written, so a refusal from the registry — one of the
            // two already absorbed by something else — leaves the case open rather than closed
            // over a merge that never happened.
            merge = registry.merge(candidate.getSubjectLowId(), candidate.getSubjectHighId(),
                    actorId);
        }

        candidate.decide(outcome, note, actorId, clock.instant());
        audit.record("RESOLUTION_DECIDED", "MatchCandidate", caseId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, outcome + ": " + note);

        return new Decision(caseId, outcome,
                merge == null ? null : merge.survivor(),
                merge == null ? 0 : merge.recordsMoved() + merge.identifiersMoved());
    }

    private boolean alreadyKnown(UUID first, UUID second) {
        UUID low = first.compareTo(second) < 0 ? first : second;
        UUID high = low.equals(first) ? second : first;
        for (MatchStatus status : MatchStatus.values()) {
            if (candidates.findBySubjectLowIdAndSubjectHighIdAndStatus(low, high, status)
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    private Case describe(MatchCandidate candidate) {
        return new Case(candidate.getId(),
                registry.describe(candidate.getSubjectLowId()),
                registry.describe(candidate.getSubjectHighId()),
                candidate.getConfidence().doubleValue(),
                readSignals(candidate.getSignals()),
                candidate.getStatus(),
                candidate.getModelVersion(),
                candidate.getDetectedAt(),
                candidate.getNote());
    }

    /**
     * The block a name belongs to.
     *
     * <p>Padded rather than truncated for short names, so "ISS" does not land in a block of its
     * own that nothing can ever join.
     */
    private static String blockKey(String normalizedName) {
        String flattened = normalizedName.replaceAll("[^a-z0-9]", "");
        return flattened.length() <= BLOCK_LENGTH
                ? flattened
                : flattened.substring(0, BLOCK_LENGTH);
    }

    private SubjectFacts facts(SubjectRegistryService.RegistrySubject subject) {
        return new SubjectFacts(subject.business(), subject.normalizedName(),
                subject.nationality(), subject.dateOfBirth(), subject.nationalIdentifiers(),
                subject.hasAccountReference());
    }

    private String asJson(List<MatchSignal> signals) {
        try {
            return json.writeValueAsString(signals);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Signals are records of enums and doubles", impossible);
        }
    }

    private List<MatchSignal> readSignals(String stored) {
        try {
            return List.of(json.readValue(stored, MatchSignal[].class));
        } catch (JsonProcessingException unreadable) {
            // Written by an older shape of this record. Better an empty evidence list on a case
            // that still shows both records than a queue that will not load at all.
            return List.of();
        }
    }

    /** Stamped on every case so a decision stays explainable when the weights move. */
    static final String MODEL_VERSION = "DIP-MR-1";

    /**
     * @param subjects how many the registry holds, so an empty queue can be told from an empty
     *                 registry
     */
    public record Scan(int subjects, int compared, int opened) {
    }

    /**
     * @param left  the older record, which is also the one a confirmation keeps
     * @param note  what the reviewer said, once there is a decision
     */
    public record Case(UUID id, SubjectRegistryService.RegistrySubject left,
                       SubjectRegistryService.RegistrySubject right, double confidence,
                       List<MatchSignal> signals, MatchStatus status, String modelVersion,
                       Instant detectedAt, String note) {
    }

    /** @param moved records and identifiers repointed, and zero for anything but a confirmation */
    public record Decision(UUID caseId, MatchStatus outcome, UUID survivor, int moved) {
    }

    public static class CaseNotFoundException extends ResourceNotFoundException {
        public CaseNotFoundException(UUID id) {
            super("Match candidate not found: " + id);
        }
    }
}
