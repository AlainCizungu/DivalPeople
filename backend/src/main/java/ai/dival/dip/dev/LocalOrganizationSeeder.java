package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.organizations.OrgUnit;
import ai.dival.dip.modules.organizations.OrgUnitRepository;
import ai.dival.dip.modules.organizations.OrgUnitService;
import ai.dival.dip.modules.organizations.OrgUnitType;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds a small organisation tree for each demo tenant.
 *
 * <p>Two levels deep with a couple of branches: enough to show indentation, mixed unit types and
 * the tenant boundary, without pretending to be a real customer's structure.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-organization", havingValue = "true")
@Order(15) // after tenants, before TIX
public class LocalOrganizationSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalOrganizationSeeder.class);

    private final OrgUnitService units;
    private final OrgUnitRepository repository;
    private final TransactionTemplate transactionTemplate;

    public LocalOrganizationSeeder(OrgUnitService units, OrgUnitRepository repository,
                                   TransactionTemplate transactionTemplate) {
        this.units = units;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedFor(LocalTenantSeeder.OPERATOR_A, "Operator A");
        seedFor(LocalTenantSeeder.OPERATOR_B, "Operator B");
    }

    private void seedFor(UUID tenantId, String label) {
        // The tenant must be bound before the transaction starts: the connection is bound to its
        // tenant at checkout, and that is what the row-level security policy reads.
        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!repository.findByTenantIdAndParentIsNull(tenantId).isEmpty()) {
                return;
            }

            OrgUnit company = units.create(
                    null, OrgUnitType.LEGAL_ENTITY, "SA", label + " SA", null);

            OrgUnit kinshasa = units.create(
                    company.getId(), OrgUnitType.BRANCH, "KIN", "Kinshasa", null);
            OrgUnit lubumbashi = units.create(
                    company.getId(), OrgUnitType.BRANCH, "FBM", "Lubumbashi", null);

            units.create(kinshasa.getId(), OrgUnitType.DEPARTMENT, "KIN-OPS", "Operations", null);
            units.create(kinshasa.getId(), OrgUnitType.DEPARTMENT, "KIN-FIN", "Finance", null);
            units.create(kinshasa.getId(), OrgUnitType.COST_CENTER, "CC-100", "Network rollout", null);
            units.create(lubumbashi.getId(), OrgUnitType.DEPARTMENT, "FBM-OPS", "Operations", null);

            log.info("Seeded organization structure for {}", label);
        }));
    }
}
