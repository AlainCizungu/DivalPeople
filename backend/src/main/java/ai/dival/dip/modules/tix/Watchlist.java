package ai.dival.dip.modules.tix;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A named group of companies one institution has decided to care about.
 *
 * <p>The list answers <em>who do I care about</em>, which is a different question from <em>what
 * changed about them</em>. A bank keeps its corporate loan book apart from its collections
 * portfolio because the two are watched for different reasons, reviewed on different rhythms, and
 * read by different people; a single flat list of every company anybody ever starred is a list
 * nobody reads.
 *
 * <p><strong>The group carries its own purpose.</strong> "Corporate loan customers, monitored for
 * the life of the facility" is a different statement from "this company is being watched because
 * we are considering financing it", and both are worth keeping — the first is the standing
 * justification a regulator asks about, the second is why this particular company is on the list
 * today. Neither substitutes for the other, so the entry keeps its own reason too.
 *
 * <p>Deleting a group does not stop monitoring what was in it. The entries survive with no group,
 * because losing a folder and deciding to stop watching a company are different decisions and only
 * one of them was made.
 */
@Entity
@Table(name = "tix_watchlist")
public class Watchlist extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    /** Why the group exists, as distinct from why any one subject is in it. */
    @Column(nullable = false)
    private String purpose;

    @Column(name = "created_by")
    private UUID createdBy;

    protected Watchlist() {
    }

    public Watchlist(String name, String purpose, UUID createdBy) {
        this.name = name;
        this.purpose = purpose;
        this.createdBy = createdBy;
    }

    /**
     * Renames the group and restates why it exists.
     *
     * <p>Both together, deliberately. A group renamed from "Under review" to "Collections" is being
     * repurposed, and letting the name change while the stated reason stays behind is how a list
     * ends up justified by a sentence describing something it no longer is.
     */
    public void rename(String newName, String newPurpose) {
        this.name = newName;
        this.purpose = newPurpose;
    }

    public String getName() {
        return name;
    }

    public String getPurpose() {
        return purpose;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
