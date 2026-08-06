package ai.dival.dip.modules.recruitment;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offers, and the hire that ends the pipeline.
 *
 * <p>Held to a narrower set of roles than the rest of recruitment because every response carries
 * a salary. A recruiter can run a pipeline; deciding what to pay is an HR decision.
 */
@RestController
@RequestMapping("/api/v1/recruitment")
public class OfferController {

    private static final String OFFER_WRITE =
            "hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    private final OfferService offers;
    private final CurrentUserService currentUser;

    public OfferController(OfferService offers, CurrentUserService currentUser) {
        this.offers = offers;
        this.currentUser = currentUser;
    }

    @GetMapping("/applications/{id}/offers")
    @PreAuthorize(OFFER_WRITE)
    public List<OfferResponse> forApplication(@PathVariable UUID id) {
        return offers.forApplication(id).stream().map(OfferResponse::from).toList();
    }

    @GetMapping("/offers/{id}")
    @PreAuthorize(OFFER_WRITE)
    public OfferResponse get(@PathVariable UUID id) {
        return OfferResponse.from(offers.get(id));
    }

    @PostMapping("/applications/{id}/offers")
    @PreAuthorize(OFFER_WRITE)
    public ResponseEntity<OfferResponse> draft(@PathVariable UUID id,
                                               @Valid @RequestBody DraftOfferRequest request) {
        JobOffer offer = offers.draft(
                id, request.jobTitle(), request.contractType(), request.proposedStartDate(),
                request.proposedEndDate(), request.salaryAmount(), request.salaryCurrency(),
                request.orgUnitId(), request.expiresOn(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(OfferResponse.from(offer));
    }

    @PostMapping("/offers/{id}/send")
    @PreAuthorize(OFFER_WRITE)
    public OfferResponse send(@PathVariable UUID id) {
        return OfferResponse.from(offers.send(id, actorId()));
    }

    /**
     * Acceptance, which creates the employee.
     *
     * <p>The employee number comes from the caller: it belongs to payroll's numbering scheme, and
     * a recruitment module inventing one is how two systems end up disagreeing about who someone
     * is.
     */
    @PostMapping("/offers/{id}/accept")
    @PreAuthorize(OFFER_WRITE)
    public HireResponse accept(@PathVariable UUID id, @Valid @RequestBody AcceptRequest request) {
        return HireResponse.from(
                offers.acceptAndHire(id, request.employeeNumber(), actorId()));
    }

    @PostMapping("/offers/{id}/decline")
    @PreAuthorize(OFFER_WRITE)
    public OfferResponse decline(@PathVariable UUID id, @RequestBody(required = false)
                                 ReasonRequest request) {
        return OfferResponse.from(
                offers.decline(id, request == null ? null : request.reason(), actorId()));
    }

    @PostMapping("/offers/{id}/withdraw")
    @PreAuthorize(OFFER_WRITE)
    public OfferResponse withdraw(@PathVariable UUID id,
                                  @Valid @RequestBody ReasonRequest request) {
        return OfferResponse.from(offers.withdraw(id, request.reason(), actorId()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    public record DraftOfferRequest(
            @NotBlank String jobTitle,
            @NotNull ContractType contractType,
            @NotNull LocalDate proposedStartDate,
            LocalDate proposedEndDate,
            BigDecimal salaryAmount,
            String salaryCurrency,
            UUID orgUnitId,
            LocalDate expiresOn) {
    }

    public record AcceptRequest(@NotBlank String employeeNumber) {
    }

    public record ReasonRequest(String reason) {
    }

    public record OfferResponse(
            UUID id,
            UUID applicationId,
            String candidateName,
            String jobTitle,
            ContractType contractType,
            BigDecimal salaryAmount,
            String salaryCurrency,
            LocalDate proposedStartDate,
            LocalDate proposedEndDate,
            LocalDate expiresOn,
            OfferStatus status,
            Instant sentAt,
            Instant respondedAt) {

        static OfferResponse from(JobOffer offer) {
            return new OfferResponse(
                    offer.getId(),
                    offer.getApplication().getId(),
                    offer.getApplication().getCandidate().displayName(),
                    offer.getJobTitle(),
                    offer.getContractType(),
                    offer.getSalaryAmount(),
                    offer.getSalaryCurrency(),
                    offer.getProposedStartDate(),
                    offer.getProposedEndDate(),
                    offer.getExpiresOn(),
                    offer.getStatus(),
                    offer.getSentAt(),
                    offer.getRespondedAt());
        }
    }

    /** What the caller needs to follow the new employee into Core HR. */
    public record HireResponse(UUID employeeId, String employeeNumber, String displayName,
                               LocalDate hireDate) {

        static HireResponse from(ai.dival.dip.modules.employees.Employee employee) {
            return new HireResponse(
                    employee.getId(),
                    employee.getEmployeeNumber(),
                    employee.displayName(),
                    employee.getHireDate());
        }
    }
}
