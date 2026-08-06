package ai.dival.dip.modules.recruitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.ContractStatus;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.employees.EmployeeStatus;
import ai.dival.dip.modules.employees.EmploymentContract;
import ai.dival.dip.modules.employees.EmploymentContractService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class OfferServiceTest extends AbstractIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private RecruitmentService recruitment;
    @Autowired
    private OfferService offers;
    @Autowired
    private EmploymentContractService contracts;
    @Autowired
    private EmployeeService employees;

    private int sequence;
    /** The approver is a foreign key to employee, so it has to be somebody real. */
    private UUID approver;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("O A", "o-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);
        approver = employees.hire("EMP-100", "Sylvie", "Mbala",
                LocalDate.of(2020, 1, 6), null, null).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A candidate carried as far as the interview stage, ready for an offer. */
    private JobApplication readyForOffer(int headcount) {
        String suffix = "-" + (++sequence);
        JobRequisition req = recruitment.createRequisition("REQ" + suffix, "Field Engineer",
                ContractType.PERMANENT, headcount, null, null, null, START, null);
        recruitment.submitRequisition(req.getId(), null);
        recruitment.approveRequisition(req.getId(), approver, null);
        recruitment.openRequisition(req.getId(), null);

        Candidate person = recruitment.registerCandidate("Marie", "Ilunga",
                "marie" + suffix + "@example.cd", CandidateSource.DIRECT, null);
        JobApplication application =
                recruitment.apply(req.getId(), person.getId(), LocalDate.now(), null);
        return recruitment.moveApplication(
                application.getId(), ApplicationStatus.INTERVIEWING, null, null);
    }

    private JobOffer sentOffer(JobApplication application) {
        JobOffer offer = offers.draft(application.getId(), "Field Engineer",
                ContractType.PERMANENT, START, null, new BigDecimal("2400.00"), "usd",
                null, START.minusMonths(1), null);
        return offers.send(offer.getId(), null);
    }

    @Test
    @DisplayName("a new offer is a draft with its currency normalised")
    void draftsOffer() {
        JobOffer offer = offers.draft(readyForOffer(1).getId(), " Field Engineer ",
                ContractType.PERMANENT, START, null, new BigDecimal("2400.00"), "usd",
                null, null, null);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.DRAFT);
        assertThat(offer.getJobTitle()).isEqualTo("Field Engineer");
        assertThat(offer.getSalaryCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("a fixed-term offer must say when the job ends")
    void refusesFixedTermWithoutEndDate() {
        JobApplication application = readyForOffer(1);

        assertThatThrownBy(() -> offers.draft(application.getId(), "Field Engineer",
                ContractType.FIXED_TERM, START, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an amount without a currency is not a salary")
    void refusesSalaryWithoutCurrency() {
        JobApplication application = readyForOffer(1);

        assertThatThrownBy(() -> offers.draft(application.getId(), "Field Engineer",
                ContractType.PERMANENT, START, null, new BigDecimal("2400.00"), null,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sending an offer moves the application to the offer stage")
    void sendingMovesApplication() {
        JobApplication application = readyForOffer(1);
        JobOffer offer = sentOffer(application);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.SENT);
        assertThat(offer.getSentAt()).isNotNull();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.OFFER);
    }

    @Test
    @DisplayName("only a sent offer can be answered")
    void refusesAnswerOnDraft() {
        JobApplication application = readyForOffer(1);
        JobOffer offer = offers.draft(application.getId(), "Field Engineer",
                ContractType.PERMANENT, START, null, null, null, null, null, null);

        assertThatThrownBy(() -> offers.acceptAndHire(offer.getId(), "EMP-900", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("accepting an offer creates the employee, the contract and fills the requisition")
    void acceptingHires() {
        JobApplication application = readyForOffer(1);
        JobOffer offer = sentOffer(application);

        Employee hired = offers.acceptAndHire(offer.getId(), "emp 900", null);

        assertThat(hired.getEmployeeNumber()).isEqualTo("EMP-900");
        assertThat(hired.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(hired.getHireDate()).isEqualTo(START);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.HIRED);
        assertThat(application.getCandidate().getEmployeeId()).isEqualTo(hired.getId());
        assertThat(application.getRequisition().getStatus()).isEqualTo(RequisitionStatus.FILLED);
        assertThat(application.getRequisition().remainingHeadcount()).isZero();
    }

    @Test
    @DisplayName("the new contract is drafted, not active: the job starts on the start date")
    void hireLeavesContractAsDraft() {
        JobApplication application = readyForOffer(1);
        Employee hired = offers.acceptAndHire(sentOffer(application).getId(), "EMP-901", null);

        List<EmploymentContract> written = contracts.forEmployee(hired.getId());

        assertThat(written).hasSize(1);
        assertThat(written.get(0).getStatus()).isEqualTo(ContractStatus.DRAFT);
        assertThat(written.get(0).getJobTitle()).isEqualTo("Field Engineer");
        assertThat(written.get(0).getStartDate()).isEqualTo(START);
        assertThat(contracts.current(hired.getId())).isEmpty();
    }

    @Test
    @DisplayName("a requisition cannot be filled beyond its authorised headcount")
    void refusesHireBeyondHeadcount() {
        JobApplication first = readyForOffer(1);
        JobRequisition requisition = first.getRequisition();
        offers.acceptAndHire(sentOffer(first).getId(), "EMP-902", null);

        Candidate second = recruitment.registerCandidate("Joseph", "Mbayo",
                "joseph@example.cd", CandidateSource.REFERRAL, null);

        // The requisition closed on the first hire, so nobody else can even apply to it.
        assertThatThrownBy(() ->
                recruitment.apply(requisition.getId(), second.getId(), LocalDate.now(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a second hire is possible while headcount remains")
    void allowsSecondHireWhileHeadcountRemains() {
        JobApplication first = readyForOffer(2);
        JobRequisition requisition = first.getRequisition();
        offers.acceptAndHire(sentOffer(first).getId(), "EMP-903", null);

        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.OPEN);
        assertThat(requisition.remainingHeadcount()).isEqualTo(1);

        Candidate second = recruitment.registerCandidate("Joseph", "Mbayo",
                "joseph2@example.cd", CandidateSource.REFERRAL, null);
        JobApplication application =
                recruitment.apply(requisition.getId(), second.getId(), LocalDate.now(), null);
        recruitment.moveApplication(application.getId(), ApplicationStatus.INTERVIEWING,
                null, null);

        offers.acceptAndHire(sentOffer(application).getId(), "EMP-904", null);

        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.FILLED);
    }

    @Test
    @DisplayName("declining closes the application as withdrawn, not rejected")
    void decliningWithdrawsApplication() {
        JobApplication application = readyForOffer(1);
        JobOffer offer = offers.decline(sentOffer(application).getId(),
                "Accepted another role", null);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.DECLINED);
        assertThat(offer.getRespondedAt()).isNotNull();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(application.getRequisition().remainingHeadcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a withdrawn offer records a reason against the application")
    void withdrawingRejectsApplication() {
        JobApplication application = readyForOffer(1);
        JobOffer offer = offers.withdraw(sentOffer(application).getId(),
                "Budget pulled", null);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.WITHDRAWN);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(application.getOutcomeReason()).isEqualTo("Budget pulled");
    }

    @Test
    @DisplayName("an answered offer cannot be withdrawn afterwards")
    void refusesWithdrawAfterAnswer() {
        JobApplication application = readyForOffer(1);
        JobOffer offer = sentOffer(application);
        offers.decline(offer.getId(), "Accepted another role", null);

        assertThatThrownBy(() -> offers.withdraw(offer.getId(), "Too late", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("offers past their deadline are swept, not left looking live")
    void expiresLapsedOffers() {
        JobApplication application = readyForOffer(1);
        JobOffer offer = sentOffer(application);

        assertThat(offers.expireLapsedOffers(START.minusMonths(2))).isZero();
        assertThat(offers.expireLapsedOffers(LocalDate.of(2026, 8, 15))).isEqualTo(1);
        assertThat(offer.getStatus()).isEqualTo(OfferStatus.EXPIRED);
    }
}
