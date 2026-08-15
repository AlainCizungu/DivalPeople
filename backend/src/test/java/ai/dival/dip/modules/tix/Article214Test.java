package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Correcting the record is the half that matters least.
 *
 * <p>Article 214 of the Code du numérique requires that a rectification or an erasure be
 * communicated « à la personne concernée elle-même ainsi qu'aux personnes à qui les données
 * inexactes […] ont été communiquées ». The people it means are the ones who refused somebody a
 * line or a loan on the strength of the wrong answer, and until this existed a wrongful listing
 * was fixed at the source and left standing in their files.
 *
 * <p>Not {@code @Transactional}: the audit rows the notification depends on are written in their
 * own transactions, and the effects commit per operator. A rolled-back test would observe none of
 * it.
 */
@RequiresDocker
class Article214Test extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private ExchangeService exchange;
    @Autowired
    private SubjectRightsService rights;
    @Autowired
    private NotificationService notifications;

    private UUID reporter;
    private UUID enquirer;
    private String document;

    /** The credit officer at the enquiring institution, who is the person article 214 protects. */
    private static final UUID OFFICER = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reporter = tenants.save(new Tenant("Reporter", "rep-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        enquirer = tenants.save(new Tenant("Enquirer", "enq-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        document = "CD-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void declare(UUID operator, String amount) {
        TenantContext.runAs(operator, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, document)),
                "Jean Kabila", Subject.SubjectType.INDIVIDUAL, LocalDate.of(1990, 5, 12), "CD",
                new BigDecimal(amount), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true), null));
    }

    private InquiryResult enquire() {
        return TenantContext.runAsResult(enquirer, () -> exchange.inquire(new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, document)),
                "Jean Kabila",
                "Credit application, file 4471"), OFFICER));
    }

    private List<Notification> officersMail() {
        return TenantContext.runAsResult(enquirer, () -> notifications.listFor(OFFICER));
    }

    // --- the obligation -----------------------------------------------------

    @Test
    @DisplayName("upholding a dispute tells the institution that was given the wrong answer")
    void upheldDisputeReachesPriorRecipients() {
        declare(reporter, "500.00");

        InquiryResult told = enquire();
        assertThat(told.outcome()).isEqualTo(InquiryResult.Outcome.OUTSTANDING_DEBT);

        // The person comes forward and is believed.
        SubjectRequest request = TenantContext.runAsResult(reporter, () -> {
            SubjectRequest raised = rights.raise(SubjectRequestType.DISPUTE,
                    IdentifierType.NATIONAL_ID, document, "That is not my debt", STAFF);
            return rights.verifyIdentity(raised.getId(), "National ID seen in person", STAFF);
        });
        TenantContext.runAs(reporter, () ->
                rights.close(request.getId(), true, "Account belongs to a different person", STAFF));

        List<Notification> mail = officersMail();

        // Correcting the database and leaving the decision standing is the failure this exists to
        // prevent. The officer refused an application on that answer.
        assertThat(mail).extracting(Notification::getMessageKey)
                .contains("tixInquiryResultSuperseded");
        assertThat(mail).filteredOn(n -> n.getMessageKey().equals("tixInquiryResultSuperseded"))
                .allSatisfy(n -> {
                    assertThat(n.getSeverity()).isEqualTo(Notification.Severity.CRITICAL);
                    // The subject id and nothing else. An obligation to notify is not a licence
                    // to disclose: not the amount, not which operator was wrong.
                    assertThat(n.getParams()).containsOnlyKeys("subject");
                    assertThat(n.getParams().get("subject")).isEqualTo(told.subjectId().toString());
                });
    }

    @Test
    @DisplayName("refusing a dispute tells nobody: the answer they were given still stands")
    void refusedDisputeNotifiesNobody() {
        declare(reporter, "500.00");
        enquire();

        SubjectRequest request = TenantContext.runAsResult(reporter, () -> {
            SubjectRequest raised = rights.raise(SubjectRequestType.DISPUTE,
                    IdentifierType.NATIONAL_ID, document, "Not mine", STAFF);
            return rights.verifyIdentity(raised.getId(), "National ID seen in person", STAFF);
        });
        TenantContext.runAs(reporter, () ->
                rights.close(request.getId(), false, "Signed contract produced", STAFF));

        assertThat(officersMail()).extracting(Notification::getMessageKey)
                .doesNotContain("tixInquiryResultSuperseded");
    }

    @Test
    @DisplayName("an institution that never enquired is not told anything")
    void nonEnquirersAreNotTold() {
        declare(reporter, "500.00");
        // Nobody enquires at all.

        SubjectRequest request = TenantContext.runAsResult(reporter, () -> {
            SubjectRequest raised = rights.raise(SubjectRequestType.DISPUTE,
                    IdentifierType.NATIONAL_ID, document, "Not mine", STAFF);
            return rights.verifyIdentity(raised.getId(), "National ID seen in person", STAFF);
        });
        TenantContext.runAs(reporter, () ->
                rights.close(request.getId(), true, "Wrong person", STAFF));

        // Article 214 reaches the people who were told, not everybody on the network. A
        // correction broadcast to institutions that never asked would itself be a disclosure.
        assertThat(officersMail()).isEmpty();
    }

    // --- the deadlines ------------------------------------------------------

    @Test
    @DisplayName("access gets ten days and everything else gets twenty")
    void statutoryPeriodsByType() {
        // Sixty and thirty until August 2026, read off articles 210, 213, 214 and 215; counsel
        // then advised ten and twenty. Asserted rather than inferred because these decide when a
        // case starts nagging, and a deadline that quietly drifts is a compliance failure that
        // looks like a working queue.
        assertThat(SubjectRequestType.ACCESS.answerWithinDays()).isEqualTo(10);
        assertThat(SubjectRequestType.DISPUTE.answerWithinDays()).isEqualTo(20);
        assertThat(SubjectRequestType.RECTIFICATION.answerWithinDays()).isEqualTo(20);
        assertThat(SubjectRequestType.ERASURE.answerWithinDays()).isEqualTo(20);

        // Access is the shortest, which is the ordering worth stating: telling somebody what is
        // held about them is the right the others depend on.
        assertThat(SubjectRequestType.ACCESS.answerWithinDays())
                .isLessThan(SubjectRequestType.RECTIFICATION.answerWithinDays());
    }

    @Test
    @DisplayName("a case carries the deadline that applied when it was opened")
    void dueDateIsSetOnRaising() {
        declare(reporter, "500.00");

        SubjectRequest access = TenantContext.runAsResult(reporter, () -> rights.raise(
                SubjectRequestType.ACCESS, IdentifierType.NATIONAL_ID, document,
                "What do you hold?", STAFF));

        long days = ChronoUnit.DAYS.between(access.getRaisedAt(), access.getDueAt());
        assertThat(days).isEqualTo(60);
    }

    @Test
    @DisplayName("an open case past its deadline is overdue; a decided one never is")
    void overdueOnlyWhileOpen() {
        declare(reporter, "500.00");

        SubjectRequest request = TenantContext.runAsResult(reporter, () -> rights.raise(
                SubjectRequestType.DISPUTE, IdentifierType.NATIONAL_ID, document,
                "Not mine", STAFF));

        assertThat(request.isOverdueAsOf(Instant.now())).isFalse();
        assertThat(request.isOverdueAsOf(Instant.now().plus(31, ChronoUnit.DAYS))).isTrue();

        // The decided instance, not the one raise() returned. Every method of the service now
        // commits its own transaction and hands back a detached entity, so the object above is a
        // snapshot of a case that was open at the time — it will report itself open forever,
        // however the real row has moved on. This test asserted against that stale copy on its
        // first run and failed, which is the standing tax on hand-managed transactions arriving
        // exactly where it was predicted.
        SubjectRequest decided = TenantContext.runAsResult(reporter, () -> {
            rights.verifyIdentity(request.getId(), "ID seen", STAFF);
            return rights.close(request.getId(), false, "Contract produced", STAFF);
        });

        // The obligation is to answer, and it has been answered. Whether it was answered late is
        // a question for the audit trail, which records when the decision was taken.
        assertThat(decided.isDecided()).isTrue();
        assertThat(decided.isOverdueAsOf(Instant.now().plus(400, ChronoUnit.DAYS))).isFalse();
    }
}
