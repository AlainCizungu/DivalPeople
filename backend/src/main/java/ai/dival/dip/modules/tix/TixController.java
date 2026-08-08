package ai.dival.dip.modules.tix;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
    private final PortfolioService portfolio;
    private final SubjectRightsService rights;
    private final CurrentUserService currentUser;

    public TixController(ExchangeService exchange, DebtRecordService debtRecords,
                         PortfolioService portfolio, SubjectRightsService rights,
                         CurrentUserService currentUser) {
        this.exchange = exchange;
        this.debtRecords = debtRecords;
        this.portfolio = portfolio;
        this.rights = rights;
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

    /**
     * The calling operator's own exposure, aged.
     *
     * <p>Guarded by the declarant role rather than the inquirer role, and the distinction is the
     * point: this is an operator reading its own books, not a question about somebody else. An
     * account that may only make inquiries has no portfolio to see.
     *
     * <p>Today's date is taken here and passed down, so that one request produces one consistent
     * picture. A service reading the clock per record could put two debts declared the same day
     * into different aging bands if the request happened to span midnight.
     */
    @GetMapping("/portfolio")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public PortfolioService.Summary portfolio() {
        return portfolio.summarise(LocalDate.now());
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

    // --- subject rights -----------------------------------------------------

    /**
     * Opens a case for somebody who has come forward about their own data.
     *
     * <p>Raising and deciding are guarded by different roles on purpose. Whoever takes the request
     * at the counter should not also be the person who rules on it — separation of duties is most
     * of what makes a rights process something other than the accused party marking its own work.
     */
    @PostMapping("/subject-requests")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<SubjectRequestResponse> raise(
            @Valid @RequestBody RaiseRequest request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(SubjectRequestResponse.from(rights.raise(
                        request.requestType(), request.identifierType(), request.identifier(),
                        request.detail(), actorId())));
    }

    @GetMapping("/subject-requests")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public List<SubjectRequestResponse> listSubjectRequests() {
        return rights.listOwn().stream().map(SubjectRequestResponse::from).toList();
    }

    @PostMapping("/subject-requests/{id}/verify-identity")
    @PreAuthorize("hasRole('" + Roles.COMPLIANCE_OFFICER + "')")
    public SubjectRequestResponse verifyIdentity(@PathVariable UUID id,
                                                 @Valid @RequestBody EvidenceRequest request) {
        return SubjectRequestResponse.from(
                rights.verifyIdentity(id, request.evidence(), actorId()));
    }

    /** The subject's whole file, across every operator. The most heavily audited read here. */
    @GetMapping("/subject-requests/{id}/disclosure")
    @PreAuthorize("hasRole('" + Roles.COMPLIANCE_OFFICER + "')")
    public List<SubjectRightsService.Disclosure> disclose(@PathVariable UUID id) {
        return rights.disclose(id, actorId());
    }

    @PostMapping("/subject-requests/{id}/decide-erasure")
    @PreAuthorize("hasRole('" + Roles.COMPLIANCE_OFFICER + "')")
    public SubjectRequestResponse decideErasure(@PathVariable UUID id) {
        return SubjectRequestResponse.from(rights.decideErasure(id, actorId()));
    }

    @PostMapping("/subject-requests/{id}/close")
    @PreAuthorize("hasRole('" + Roles.COMPLIANCE_OFFICER + "')")
    public SubjectRequestResponse close(@PathVariable UUID id,
                                        @Valid @RequestBody CloseRequest request) {
        return SubjectRequestResponse.from(
                rights.close(id, request.upheld(), request.reason(), actorId()));
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

    public record RaiseRequest(
            @jakarta.validation.constraints.NotNull SubjectRequestType requestType,
            @jakarta.validation.constraints.NotNull IdentifierType identifierType,
            @jakarta.validation.constraints.NotBlank String identifier,
            @jakarta.validation.constraints.Size(max = 2000) String detail) {
    }

    public record EvidenceRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 500) String evidence) {
    }

    public record CloseRequest(
            boolean upheld,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 1000) String reason) {
    }

    /**
     * A case, as shown to staff handling it.
     *
     * <p>Carries no identifier and no name. Whoever is progressing the case already knows who
     * walked in; a list endpoint that echoed identity documents back would turn the case queue
     * into a second copy of the registry with weaker controls around it.
     */
    public record SubjectRequestResponse(
            UUID id,
            SubjectRequestType requestType,
            SubjectRequestStatus status,
            String detail,
            java.time.Instant raisedAt,
            java.time.Instant dueAt,
            boolean overdue,
            java.time.Instant identityVerifiedAt,
            java.time.Instant decidedAt,
            String decisionReason) {

        static SubjectRequestResponse from(SubjectRequest request) {
            return new SubjectRequestResponse(
                    request.getId(),
                    request.getRequestType(),
                    request.getStatus(),
                    request.getDetail(),
                    request.getRaisedAt(),
                    request.getDueAt(),
                    // Computed here rather than left to the client. Whether a case has run out of
                    // time is a fact about a statutory deadline, and two clients disagreeing about
                    // it because one of them is in a different timezone is not a rendering
                    // difference.
                    request.isOverdueAsOf(java.time.Instant.now()),
                    request.getIdentityVerifiedAt(),
                    request.getDecidedAt(),
                    request.getDecisionReason());
        }
    }
}
