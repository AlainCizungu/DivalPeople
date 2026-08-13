package ai.dival.dip.modules.anomalies;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How this institution's own people have been using the exchange.
 *
 * <p>The compliance officer or the tenant administrator, and nobody else. This reads colleagues'
 * behaviour, which is a supervisory function rather than a working one — a declarant looking at
 * how much a rival team is enquiring is not oversight, it is office politics with a data feed.
 *
 * <p>Tenant-scoped like everything else here: an operator sees its own users. Whether the registry
 * should watch across operators is a real question and a different one, and answering it would
 * mean deciding who supervises a participant, which is a matter for the terms of participation
 * rather than for a controller.
 */
@RestController
@RequestMapping("/api/v1/anomalies")
public class AnomalyController {

    private final AnomalyService anomalies;
    private final CurrentUserService currentUser;

    public AnomalyController(AnomalyService anomalies, CurrentUserService currentUser) {
        this.anomalies = anomalies;
        this.currentUser = currentUser;
    }

    @GetMapping("/behaviour")
    @PreAuthorize("hasAnyRole('" + Roles.COMPLIANCE_OFFICER + "', '" + Roles.TENANT_ADMIN + "')")
    public AnomalyService.Report behaviour() {
        return anomalies.forOperator(actorId());
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }
}
