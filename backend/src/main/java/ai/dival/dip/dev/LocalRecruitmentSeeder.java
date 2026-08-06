package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.organizations.OrgUnit;
import ai.dival.dip.modules.organizations.OrgUnitRepository;
import ai.dival.dip.modules.recruitment.ApplicationStatus;
import ai.dival.dip.modules.recruitment.Candidate;
import ai.dival.dip.modules.recruitment.CandidateSource;
import ai.dival.dip.modules.recruitment.InterviewMode;
import ai.dival.dip.modules.recruitment.InterviewRecommendation;
import ai.dival.dip.modules.recruitment.InterviewStage;
import ai.dival.dip.modules.recruitment.JobApplication;
import ai.dival.dip.modules.recruitment.JobRequisition;
import ai.dival.dip.modules.recruitment.JobRequisitionRepository;
import ai.dival.dip.modules.recruitment.OfferService;
import ai.dival.dip.modules.recruitment.RecruitmentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Seeds a hiring pipeline for operator A, with candidates spread across the stages.
 *
 * <p>A single-stage pipeline shows nothing, so this leaves one person at screening, one mid
 * interview, one holding an offer and one rejected with a reason — the pipeline board is only
 * worth looking at when the columns differ.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-recruitment", havingValue = "true")
@Order(19) // after employees, before TIX
public class LocalRecruitmentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalRecruitmentSeeder.class);

    private final RecruitmentService recruitment;
    private final OfferService offers;
    private final JobRequisitionRepository requisitions;
    private final OrgUnitRepository orgUnits;
    private final TransactionTemplate transactionTemplate;

    public LocalRecruitmentSeeder(RecruitmentService recruitment, OfferService offers,
                                  JobRequisitionRepository requisitions,
                                  OrgUnitRepository orgUnits,
                                  TransactionTemplate transactionTemplate) {
        this.recruitment = recruitment;
        this.offers = offers;
        this.requisitions = requisitions;
        this.orgUnits = orgUnits;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!requisitions.findByTenantIdOrderByCreatedAtDesc(tenantId).isEmpty()) {
                return;
            }

            List<OrgUnit> units = orgUnits.findByTenantIdOrderByDepthAscNameAsc(tenantId);
            UUID operations = units.stream()
                    .filter(unit -> unit.getCode().equals("KIN-OPS"))
                    .map(OrgUnit::getId)
                    .findFirst()
                    .orElse(null);

            JobRequisition req = recruitment.createRequisition(
                    "REQ-2026-014", "Field Network Engineer", ContractType.PERMANENT, 2,
                    operations, null,
                    "Two engineers for the Kinshasa tower maintenance rotation.",
                    LocalDate.now().plusMonths(2), null);
            recruitment.submitRequisition(req.getId(), null);
            recruitment.approveRequisition(req.getId(), UUID.randomUUID(), null);
            recruitment.openRequisition(req.getId(), null);

            apply(req, "Espérance", "Nsimba", "esperance.nsimba@example.cd",
                    CandidateSource.JOB_BOARD, ApplicationStatus.SCREENING);

            JobApplication interviewing = apply(req, "Didier", "Lokwa",
                    "didier.lokwa@example.cd", CandidateSource.REFERRAL,
                    ApplicationStatus.INTERVIEWING);
            recruitment.submitInterviewFeedback(
                    recruitment.scheduleInterview(interviewing.getId(), InterviewStage.SCREENING,
                            InterviewMode.PHONE, Instant.now().minus(6, ChronoUnit.DAYS),
                            null, null).getId(),
                    InterviewRecommendation.YES, 4, "Solid on RF fundamentals.", null);
            recruitment.scheduleInterview(interviewing.getId(), InterviewStage.TECHNICAL,
                    InterviewMode.ON_SITE, Instant.now().plus(2, ChronoUnit.DAYS), null, null);

            JobApplication offered = apply(req, "Grâce", "Tshibangu",
                    "grace.tshibangu@example.cd", CandidateSource.DIRECT,
                    ApplicationStatus.INTERVIEWING);
            offers.send(offers.draft(offered.getId(), "Field Network Engineer",
                    ContractType.PERMANENT, LocalDate.now().plusMonths(2), null,
                    new BigDecimal("2400.00"), "USD", operations,
                    LocalDate.now().plusDays(10), null).getId(), null);

            JobApplication rejected = apply(req, "Olivier", "Mwamba",
                    "olivier.mwamba@example.cd", CandidateSource.AGENCY,
                    ApplicationStatus.SCREENING);
            recruitment.moveApplication(rejected.getId(), ApplicationStatus.REJECTED,
                    "No tower climbing certification.", null);

            log.info("Seeded 1 open requisition and 4 candidates for operator A");
        }));
    }

    private JobApplication apply(JobRequisition requisition, String firstName, String lastName,
                                 String email, CandidateSource source, ApplicationStatus stage) {
        Candidate candidate =
                recruitment.registerCandidate(firstName, lastName, email, source, null);
        JobApplication application = recruitment.apply(requisition.getId(), candidate.getId(),
                LocalDate.now().minusDays(12), null);
        if (stage != ApplicationStatus.APPLIED) {
            recruitment.moveApplication(application.getId(), stage, null, null);
        }
        return application;
    }
}
