package ai.dival.dip.modules.resolution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Two subjects that may be one, and everything a reviewer needs to decide.
 *
 * <p>Holds subject ids rather than a relationship to {@code Subject}, which lives in {@code tix}.
 * A mapping between the two modules' entities would make every schema change in the registry a
 * change here; the foreign keys are real and enforced by the database, and what is avoided is the
 * compile-time coupling.
 *
 * <p><strong>The signals are stored, not recomputed.</strong> They are the evidence a person
 * decided on, and the weights behind them will move. Recomputing at read time would show a
 * reviewer's conclusion beside numbers they never saw, which is worse than useless in the one
 * place somebody may later have to justify a merge.
 */
@Entity
@Table(name = "tix_match_candidate")
public class MatchCandidate {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * The pair, ordered so it is one row however the scan found it.
     *
     * <p>Without the ordering a scan that compared B against A would open a second case about the
     * same two records, and a reviewer could confirm one and reject the other. The database
     * enforces the ordering; this constructor is what makes it true.
     */
    @Column(name = "subject_low_id", nullable = false, updatable = false)
    private UUID subjectLowId;

    @Column(name = "subject_high_id", nullable = false, updatable = false)
    private UUID subjectHighId;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3, updatable = false)
    private BigDecimal confidence;

    /** The signals as JSON, exactly as the scorer produced them. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signals", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String signals;

    @Column(name = "model_version", nullable = false, updatable = false, length = 40)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MatchStatus status = MatchStatus.OPEN;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "note")
    private String note;

    protected MatchCandidate() {
        // for JPA
    }

    public MatchCandidate(UUID first, UUID second, double confidence, String signals,
                          String modelVersion, Instant detectedAt) {
        if (first.equals(second)) {
            throw new IllegalArgumentException("A subject is not a candidate match for itself");
        }
        // Ordered here rather than by the caller. Every call site would otherwise have to remember,
        // and the one that forgot would create the duplicate case the unique index exists to stop
        // — as a constraint violation at flush time, a long way from the mistake.
        boolean ascending = compareAsDatabase(first, second) < 0;
        this.subjectLowId = ascending ? first : second;
        this.subjectHighId = ascending ? second : first;
        this.confidence = BigDecimal.valueOf(confidence).setScale(3, java.math.RoundingMode.HALF_UP);
        this.signals = signals;
        this.modelVersion = modelVersion;
        this.detectedAt = detectedAt;
    }

    /**
     * Orders two ids the way PostgreSQL does, which is not the way Java does.
     *
     * <p>{@code UUID.compareTo} compares the most significant bits as a <strong>signed</strong>
     * long, so any id with the top bit set sorts before every id without one. PostgreSQL compares
     * {@code uuid} as unsigned bytes. The two disagree on almost exactly half of all random pairs,
     * which is what {@code subject_low_id &lt; subject_high_id} caught: rows ordered correctly by
     * Java arrived at a CHECK constraint that read them the other way round and refused them.
     *
     * <p>Half is the worst possible failure rate for noticing. A tenth would have looked like a
     * flake and been retried; everything failing would have been found in the first run. Half
     * looked like a feature that worked in some tests and not others, and sent me hunting the
     * differences between the tests.
     *
     * <p>The database's ordering wins, because the database is where the constraint lives.
     */
    static int compareAsDatabase(UUID left, UUID right) {
        int high = Long.compareUnsigned(
                left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(
                left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    /**
     * Records a decision.
     *
     * <p>Refuses a null actor and refuses to decide twice, for the same reason the rights queue
     * does: this is the record of who merged two companies' files, and one that names nobody is
     * not a record. Re-deciding would let a confirmation be quietly turned into a rejection with
     * no trace that anything had been confirmed.
     */
    void decide(MatchStatus outcome, String note, UUID actorId, Instant when) {
        if (status != MatchStatus.OPEN && status != MatchStatus.INVESTIGATING) {
            throw new IllegalStateException("This case has already been decided");
        }
        if (outcome == MatchStatus.OPEN) {
            throw new IllegalArgumentException("A decision cannot be to leave it open");
        }
        this.status = outcome;
        this.note = note;
        this.decidedBy = actorId;
        this.decidedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubjectLowId() {
        return subjectLowId;
    }

    public UUID getSubjectHighId() {
        return subjectHighId;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getSignals() {
        return signals;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getNote() {
        return note;
    }
}
