package ai.dival.dip.modules.tenants;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * An email domain that identifies an institution.
 *
 * <p>A person who registers with an address at this domain, once they have proven they can read
 * mail there, joins this institution — with no roles and no access to anything until an
 * administrator there grants some.
 *
 * <p>No setter and nothing mutable after insert. A domain that could be edited in place is a domain
 * that could be pointed at a different institution without leaving a trace, and every account that
 * joined under the old value would already be inside the wrong book. Changing a mapping means
 * deleting a row and adding another, which is two audited acts instead of one silent one.
 */
@Entity
@Table(name = "tenant_email_domain")
public class TenantEmailDomain extends TenantOwnedEntity {

    /**
     * Lower-cased, and the database refuses anything else.
     *
     * <p>Domains are case-insensitive, so a row stored as {@code Vodacom.cd} would simply never
     * match an address — silently, with no error anywhere, and the symptom would be new joiners
     * being told their organisation is not on DIP while the mapping sits there looking correct.
     */
    @Column(name = "domain", nullable = false, updatable = false)
    private String domain;

    protected TenantEmailDomain() {
    }

    public TenantEmailDomain(String domain) {
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }
}
