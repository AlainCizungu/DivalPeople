package ai.dival.dip.modules.learning;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Locale;

/** Something people can be trained on. */
@Entity
@Table(name = "course")
public class Course extends TenantOwnedEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @Column(name = "provider", length = 200)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 20)
    private DeliveryMode deliveryMode = DeliveryMode.ONLINE;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /**
     * Everybody must hold this.
     *
     * <p>A flag rather than a requirements table, which is deferred deliberately: per-role
     * requirements are a real need and a bigger design, and guessing at one now would be worse
     * than a flag that is honest about its scope.
     */
    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    /** How long a pass stays valid. Null means it never expires. */
    @Column(name = "validity_months")
    private Integer validityMonths;

    @Column(name = "pass_score")
    private Integer passScore;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Course() {
        // for JPA
    }

    public Course(String code, String title, DeliveryMode deliveryMode) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A course needs a title");
        }
        this.code = normalizeCode(code);
        this.title = title.trim();
        this.deliveryMode = deliveryMode == null ? DeliveryMode.ONLINE : deliveryMode;
        this.active = true;
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    public void describe(String description, String provider, Integer durationMinutes) {
        if (durationMinutes != null && durationMinutes <= 0) {
            throw new IllegalArgumentException("A duration must be positive");
        }
        this.description = description;
        this.provider = provider;
        this.durationMinutes = durationMinutes;
    }

    public void setPolicy(boolean mandatory, Integer validityMonths, Integer passScore) {
        if (validityMonths != null && validityMonths <= 0) {
            throw new IllegalArgumentException(
                    "A validity period must be positive, or absent to mean it never expires");
        }
        if (passScore != null && (passScore < 0 || passScore > 100)) {
            throw new IllegalArgumentException("A pass mark is a percentage");
        }
        this.mandatory = mandatory;
        this.validityMonths = validityMonths;
        this.passScore = passScore;
    }

    /** Retired rather than deleted: enrolments already point at it. */
    public void retire() {
        this.active = false;
    }

    /**
     * When a pass on this course, completed on a given day, would lapse.
     *
     * @return null when the qualification does not expire
     */
    public LocalDate expiryFor(LocalDate completedOn) {
        return validityMonths == null ? null : completedOn.plusMonths(validityMonths);
    }

    /** Whether a score is a pass. A course with no pass mark passes on completion alone. */
    public boolean isPass(Integer score) {
        if (passScore == null) {
            return true;
        }
        return score != null && score >= passScore;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getProvider() {
        return provider;
    }

    public DeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public Integer getValidityMonths() {
        return validityMonths;
    }

    public Integer getPassScore() {
        return passScore;
    }

    public boolean isActive() {
        return active;
    }
}
