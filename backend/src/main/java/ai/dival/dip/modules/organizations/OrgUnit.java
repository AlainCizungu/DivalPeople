package ai.dival.dip.modules.organizations;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Locale;

/**
 * A node in a tenant's organisation: a legal entity, branch, department, cost center or location.
 *
 * <p>Structure is expressed by {@code parent}, so the tree can be any shape the customer needs.
 * Depth is kept alongside for display, but nothing structural trusts it — subtree questions are
 * answered by querying the parent chain, so a stale depth can never produce a wrong answer about
 * containment.
 */
@Entity
@Table(name = "org_unit")
public class OrgUnit extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private OrgUnit parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 30)
    private OrgUnitType unitType;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "depth", nullable = false)
    private int depth;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected OrgUnit() {
        // for JPA
    }

    public OrgUnit(OrgUnitType unitType, String code, String name, OrgUnit parent) {
        this.unitType = unitType;
        this.code = normalizeCode(code);
        this.name = name == null ? null : name.trim();
        this.parent = parent;
        this.depth = parent == null ? 0 : parent.getDepth() + 1;
        this.active = true;
    }

    /** Codes are matched by integrations and imports, so case and spacing must not vary. */
    public static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    public void rename(String name) {
        this.name = name == null ? this.name : name.trim();
    }

    /**
     * Reattaches this unit under a new parent.
     *
     * <p>Cycle detection belongs to the service, which can see the whole tree. An entity cannot
     * safely decide this alone: the offending ancestor may not be loaded.
     */
    void reattachTo(OrgUnit newParent) {
        this.parent = newParent;
        this.depth = newParent == null ? 0 : newParent.getDepth() + 1;
    }

    void setDepth(int depth) {
        this.depth = depth;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public OrgUnit getParent() {
        return parent;
    }

    public OrgUnitType getUnitType() {
        return unitType;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getDepth() {
        return depth;
    }

    public boolean isActive() {
        return active;
    }
}
