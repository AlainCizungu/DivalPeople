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

    /**
     * Declare that a subscriber has defaulted.
     *
     * <p>The writing end of the exchange, and the endpoint the module shipped without. Note what
     * the response does <em>not</em> contain: the subject id is returned because the declaring
     * operator now legitimately holds a record against that person, but nothing tells the caller
     * whether any other operator does. Reporting a default must not double as a free inquiry —
     * otherwise the cheapest way to search the registry is to declare a debt and read the reply.
     */
    @PostMapping("/debt-records")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<DeclarationResponse> declare(
            @Valid @RequestBody DeclarationRequest request) {
        DebtRecordService.Declaration declared = debtRecords.declare(request, actorId());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(new DeclarationResponse(
                        DebtRecordResponse.from(declared.record()),
                        declared.subjectWasCreated(),
                        declared.identifiersLearned()));
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

    /**
     * What a declaration produced.
     *
     * @param subjectWasCreated whether this put a person into the exchange who was not in it
     *                          before. The declaring operator is entitled to know: it is the
     *                          difference between adding to a file and opening one.
     */
    public record DeclarationResponse(DebtRecordResponse record, boolean subjectWasCreated,
                                      int identifiersLearned) {
    }

    /**
     * Response projection — the entity is never serialised directly.
     *
     * <p>Carries the amount and the retention date, and both are deliberate. This projection is
     * only ever returned to the operator that <em>declared</em> the record, from
     * {@code GET /debt-records} and the settle and dispute endpoints. Withholding an operator's
     * own figure from it protects nobody, and the retention date is the answer to the question a
     * declarant most needs to be able to answer — "when does this stop being visible" — which
     * until now existed only in the database.
     *
     * <p>Nothing here crosses the exchange. An enquiring operator sees {@link InquiryResult},
     * which has never carried an amount and still does not.
     */
    public record DebtRecordResponse(
            UUID id,
            UUID subjectId,
            DebtStatus status,
            java.math.BigDecimal amount,
            String currency,
            String serviceCategory,
            java.time.LocalDate defaultDate,
            java.time.LocalDate retentionUntil) {

        static DebtRecordResponse from(DebtRecord record) {
            return new DebtRecordResponse(
                    record.getId(),
                    record.getSubject().getId(),
                    record.getStatus(),
                    record.getAmount(),
                    record.getCurrency(),
                    record.getServiceCategory(),
                    record.getDefaultDate(),
                    record.getRetentionUntil());
        }
    }
}
