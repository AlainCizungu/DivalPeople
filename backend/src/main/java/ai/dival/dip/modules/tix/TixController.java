package ai.dival.dip.modules.tix;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TIX API.
 *
 * <p>Authorization is declared per endpoint and enforced server-side. The tenant is never accepted
 * as a parameter; it comes from the authenticated principal.
 */
@RestController
@RequestMapping("/api/v1/tix")
public class TixController {

    private final ExchangeService exchange;
    private final DebtRecordService debtRecords;
    private final CurrentUserService currentUser;

    public TixController(ExchangeService exchange, DebtRecordService debtRecords,
                         CurrentUserService currentUser) {
        this.exchange = exchange;
        this.debtRecords = debtRecords;
        this.currentUser = currentUser;
    }

    /** Verify a prospective customer before activating a credit-bearing service. */
    @PostMapping("/inquiries")
    @PreAuthorize("hasRole('" + Roles.TIX_INQUIRER + "')")
    public InquiryResult inquire(@Valid @RequestBody InquiryRequest request) {
        return exchange.inquire(request, actorId());
    }

    /** Records declared by the calling operator. */
    @GetMapping("/debt-records")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public List<DebtRecordResponse> listOwnDebtRecords() {
        return debtRecords.listOwn().stream().map(DebtRecordResponse::from).toList();
    }

    @PostMapping("/debt-records/{id}/settle")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<DebtRecordResponse> settle(@PathVariable UUID id) {
        return ResponseEntity.ok(DebtRecordResponse.from(debtRecords.settle(id, actorId())));
    }

    @PostMapping("/debt-records/{id}/dispute")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<DebtRecordResponse> dispute(@PathVariable UUID id) {
        return ResponseEntity.ok(DebtRecordResponse.from(debtRecords.dispute(id, actorId())));
    }

    /**
     * The local user id, so audit entries point at a record that can be joined to a person
     * rather than at an opaque token subject.
     */
    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    /** Response projection — the entity is never serialised directly. */
    public record DebtRecordResponse(
            UUID id,
            UUID subjectId,
            DebtStatus status,
            String currency,
            String serviceCategory,
            java.time.LocalDate defaultDate) {

        static DebtRecordResponse from(DebtRecord record) {
            return new DebtRecordResponse(
                    record.getId(),
                    record.getSubject().getId(),
                    record.getStatus(),
                    record.getCurrency(),
                    record.getServiceCategory(),
                    record.getDefaultDate());
        }
    }
}
