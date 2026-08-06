package ai.dival.dip.modules.tix;

import ai.dival.dip.common.security.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    public TixController(ExchangeService exchange, DebtRecordService debtRecords) {
        this.exchange = exchange;
        this.debtRecords = debtRecords;
    }

    /** Verify a prospective customer before activating a credit-bearing service. */
    @PostMapping("/inquiries")
    @PreAuthorize("hasRole('" + Roles.TIX_INQUIRER + "')")
    public InquiryResult inquire(@Valid @RequestBody InquiryRequest request,
                                 @AuthenticationPrincipal Jwt principal) {
        return exchange.inquire(request, actorId(principal));
    }

    /** Records declared by the calling operator. */
    @GetMapping("/debt-records")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public List<DebtRecordResponse> listOwnDebtRecords() {
        return debtRecords.listOwn().stream().map(DebtRecordResponse::from).toList();
    }

    @PostMapping("/debt-records/{id}/settle")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<DebtRecordResponse> settle(@PathVariable UUID id,
                                                     @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok(DebtRecordResponse.from(debtRecords.settle(id, actorId(principal))));
    }

    @PostMapping("/debt-records/{id}/dispute")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<DebtRecordResponse> dispute(@PathVariable UUID id,
                                                      @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok(DebtRecordResponse.from(debtRecords.dispute(id, actorId(principal))));
    }

    private UUID actorId(Jwt principal) {
        if (principal == null || principal.getSubject() == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getSubject());
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
