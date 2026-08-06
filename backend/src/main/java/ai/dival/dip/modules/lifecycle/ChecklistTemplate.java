package ai.dival.dip.modules.lifecycle;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A reusable list — "engineer onboarding", "standard offboarding".
 *
 * <p>A starting point, not a live reference. Raising a checklist copies these items into the
 * employee's own list, so editing the template next year cannot rewrite what somebody was
 * actually asked to do last year.
 */
@Entity
@Table(name = "checklist_template")
public class ChecklistTemplate extends TenantOwnedEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "checklist_type", nullable = false, length = 20)
    private ChecklistType checklistType;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ChecklistTemplateItem> items = new ArrayList<>();

    protected ChecklistTemplate() {
        // for JPA
    }

    public ChecklistTemplate(String code, String name, ChecklistType checklistType) {
        this.code = normalizeCode(code);
        this.name = name == null ? null : name.trim();
        this.checklistType = checklistType;
        this.active = true;
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    /**
     * Adds a step.
     *
     * @param dueOffsetDays days from the anchor date; negative for work that must happen before
     *                      someone starts, which is most of what makes a first day go well
     */
    public ChecklistTemplateItem addItem(String title, String instructions, ItemCategory category,
                                         String ownerRole, int dueOffsetDays, boolean mandatory) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A checklist item needs a title");
        }
        ChecklistTemplateItem item = new ChecklistTemplateItem(
                this, items.size() + 1, title, instructions, category, ownerRole,
                dueOffsetDays, mandatory);
        items.add(item);
        return item;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A template needs a name");
        }
        this.name = name.trim();
    }

    /** Retired, not deleted: the lists it already raised have to stay explainable. */
    public void retire() {
        this.active = false;
    }

    public void reinstate() {
        this.active = true;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public ChecklistType getChecklistType() {
        return checklistType;
    }

    public boolean isActive() {
        return active;
    }

    public List<ChecklistTemplateItem> getItems() {
        return List.copyOf(items);
    }
}
