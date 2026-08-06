package ai.dival.dip.modules.lifecycle;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One step in a template. Copied, never referenced, once a checklist is raised from it. */
@Entity
@Table(name = "checklist_template_item")
public class ChecklistTemplateItem extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ChecklistTemplate template;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "instructions", length = 2000)
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ItemCategory category = ItemCategory.OTHER;

    /** A role rather than a person: a template naming an individual breaks the day they leave. */
    @Column(name = "owner_role", length = 50)
    private String ownerRole;

    @Column(name = "due_offset_days", nullable = false)
    private int dueOffsetDays;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    protected ChecklistTemplateItem() {
        // for JPA
    }

    ChecklistTemplateItem(ChecklistTemplate template, int sortOrder, String title,
                          String instructions, ItemCategory category, String ownerRole,
                          int dueOffsetDays, boolean mandatory) {
        this.template = template;
        this.sortOrder = sortOrder;
        this.title = title.trim();
        this.instructions = instructions;
        this.category = category == null ? ItemCategory.OTHER : category;
        this.ownerRole = ownerRole;
        this.dueOffsetDays = dueOffsetDays;
        this.mandatory = mandatory;
    }

    public ChecklistTemplate getTemplate() {
        return template;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getTitle() {
        return title;
    }

    public String getInstructions() {
        return instructions;
    }

    public ItemCategory getCategory() {
        return category;
    }

    public String getOwnerRole() {
        return ownerRole;
    }

    public int getDueOffsetDays() {
        return dueOffsetDays;
    }

    public boolean isMandatory() {
        return mandatory;
    }
}
