package ai.dival.dip.modules.lifecycle;

/** What kind of work an item is, which is what makes a list scannable rather than a wall. */
public enum ItemCategory {

    PAPERWORK,
    EQUIPMENT,

    /** Systems, badges, keys. The category that matters most on the way out. */
    ACCESS,

    PAYROLL,
    TRAINING,
    INTRODUCTION,
    COMPLIANCE,
    OTHER
}
