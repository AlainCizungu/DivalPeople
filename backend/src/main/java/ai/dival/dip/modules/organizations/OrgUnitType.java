package ai.dival.dip.modules.organizations;

/**
 * What a unit represents.
 *
 * <p>The type carries meaning for reporting and compliance but deliberately does not dictate the
 * shape of the tree: organisations nest these differently, and a fixed hierarchy is contradicted
 * by the first customer who does it their own way. The one structural rule is that a root must be
 * a legal entity, because everything else ultimately belongs to one.
 */
public enum OrgUnitType {

    /** A registered company. The only type permitted at the root of a tree. */
    LEGAL_ENTITY,

    BRANCH,
    DEPARTMENT,

    /** Carries cost allocation rather than people; referenced by payroll. */
    COST_CENTER,

    /** A physical site, which may hold people from several departments. */
    LOCATION;

    public boolean canBeRoot() {
        return this == LEGAL_ENTITY;
    }
}
