package ai.dival.dip.modules.performance;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What somebody other than the reviewer had to say.
 *
 * <p>The author is always stored. Whether the subject gets to see the name is a separate
 * decision: feedback nobody can attribute is unaccountable, and feedback whose author is always
 * exposed is feedback nobody gives honestly. Keeping the author and controlling attribution
 * separately is the only arrangement that serves both.
 */
@Entity
@Table(name = "review_feedback")
public class ReviewFeedback extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private PerformanceReview review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private Employee author;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, length = 20)
    private FeedbackRelationship relationship;

    @Column(name = "comments", nullable = false, length = 4000)
    private String comments;

    /** False means the subject reads the words but not the name. HR always sees both. */
    @Column(name = "attributed", nullable = false)
    private boolean attributed;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    protected ReviewFeedback() {
        // for JPA
    }

    public ReviewFeedback(PerformanceReview review, Employee author,
                          FeedbackRelationship relationship, String comments,
                          boolean attributed) {
        if (comments == null || comments.isBlank()) {
            throw new IllegalArgumentException("Feedback needs something written in it");
        }
        this.review = review;
        this.author = author;
        this.relationship = relationship;
        this.comments = comments;
        this.attributed = attributed;
        this.submittedAt = Instant.now();
    }

    /** The author's name, or null when they asked not to be named to the subject. */
    public String authorNameFor(boolean askingAsSubject) {
        if (!askingAsSubject) {
            return author.displayName();
        }
        return attributed ? author.displayName() : null;
    }

    public PerformanceReview getReview() {
        return review;
    }

    public Employee getAuthor() {
        return author;
    }

    public FeedbackRelationship getRelationship() {
        return relationship;
    }

    public String getComments() {
        return comments;
    }

    public boolean isAttributed() {
        return attributed;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
