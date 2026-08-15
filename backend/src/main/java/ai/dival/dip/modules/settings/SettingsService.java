package ai.dival.dip.modules.settings;

import ai.dival.dip.modules.resolution.MatchAssessment;
import ai.dival.dip.modules.risk.RiskIndicatorService;
import ai.dival.dip.modules.tix.SubjectRequestType;
import ai.dival.dip.modules.tix.TixProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * What this deployment is actually configured to do.
 *
 * <p>Every value here already existed and none of it was visible. It lives in
 * {@code application.yml}, which is read by whoever deploys the application and by nobody else —
 * so an operator could not find out how long their records are kept, a compliance officer could
 * not check the retention period against the law, and a subject asking when their entry expires
 * had to be answered from memory.
 *
 * <p><strong>Provenance travels with every value.</strong> Three of these numbers are the terms of
 * reference's illustrative figures and have never been checked against the Code du numérique —
 * {@code TixProperties.Retention} says so in its own javadoc and the yaml says so in a comment
 * that only a deployer reads. A retention period is the difference between a lawful registry and
 * an unlawful one, and "3 years" printed with no qualification reads as a decision somebody took.
 * It is not one yet. So each setting says whether it was decided, defaulted, or is a placeholder
 * waiting on a person.
 *
 * <p>Read-only, and that is a decision rather than a first step postponed. Editing a retention
 * period moves the erasure date of every record in the registry, some of them past due the moment
 * it is shortened. That change needs a decision, an audit entry and probably a migration of the
 * dates already written; a text box on a settings page is not the shape of it.
 */
@Service
public class SettingsService {

    private final TixProperties tix;
    private final int inquiriesPerHour;
    private final String retentionPurgeCron;

    public SettingsService(TixProperties tix,
                           @Value("${dip.tix.inquiries-per-hour:120}") int inquiriesPerHour,
                           @Value("${dip.tix.retention-purge-cron:0 15 2 * * *}")
                           String retentionPurgeCron) {
        this.tix = tix;
        this.inquiriesPerHour = inquiriesPerHour;
        this.retentionPurgeCron = retentionPurgeCron;
    }

    public Settings effective() {
        return new Settings(retention(), rights(), reporting(), exchange(), models());
    }

    /**
     * How long somebody stays in the registry.
     *
     * <p>The section that earns this whole screen, and the one that changed most in August 2026.
     * Two of these were the TDR's illustrative figures that nobody had checked against the Code;
     * counsel answered five years and they now say so. The third was never asked about and is
     * still a placeholder — which is precisely why the provenance column has to distinguish them,
     * because rendered as numbers on a page the three look identical.
     */
    private List<Setting> retention() {
        TixProperties.Retention periods = tix.retention();
        return List.of(
                new Setting("RETENTION_SIMPLE_YEARS", String.valueOf(periods.simpleYears()),
                        "YEARS", Provenance.LEGAL_ADVICE),
                new Setting("RETENTION_REPEAT_YEARS", String.valueOf(periods.repeatYears()),
                        "YEARS", Provenance.LEGAL_ADVICE),
                // Not asked, not answered, still a plausible number nobody has checked.
                new Setting("RETENTION_SETTLED_DAYS", String.valueOf(periods.settledDays()),
                        "DAYS", Provenance.UNVERIFIED_PLACEHOLDER),
                new Setting("RETENTION_PURGE_SCHEDULE", retentionPurgeCron,
                        "CRON", Provenance.OPERATIONAL_DEFAULT));
    }

    /**
     * How long the registry has to answer a person asking about themselves.
     *
     * <p>Shown because they moved a long way and because nothing else on any screen would say so.
     * Counsel replaced sixty days for access and thirty for everything else with ten and twenty,
     * which turns a comfortable queue into one with real pressure on it — an operator who does not
     * know the number changed will discover it as a run of overdue cases.
     *
     * <p>Compiled rather than configured, and listed here anyway. A statutory deadline belongs to
     * the statute; making it tunable would invite somebody to tune it, and a reader who cannot see
     * it has to take the queue's arithmetic on trust.
     */
    private List<Setting> rights() {
        return List.of(
                new Setting("RIGHTS_ACCESS_DAYS",
                        String.valueOf(SubjectRequestType.ACCESS.answerWithinDays()),
                        "DAYS", Provenance.LEGAL_ADVICE),
                new Setting("RIGHTS_OTHER_DAYS",
                        String.valueOf(SubjectRequestType.DISPUTE.answerWithinDays()),
                        "DAYS", Provenance.LEGAL_ADVICE));
    }

    /**
     * The floor below which nothing enters the registry, per currency.
     *
     * <p>A currency with no floor is refused rather than defaulted, so the absence of a row here
     * is itself the setting — and the most consequential thing on the page is the row that is not
     * there. CDF is deliberately unset: choosing it means choosing a USD/CDF rate, which moves and
     * which somebody accountable has to own.
     */
    private List<Setting> reporting() {
        List<Setting> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> floor : tix.minimumDeclarable().entrySet()) {
            // Was the TDR's suggestion; counsel confirmed 100 USD in August 2026, which promotes
            // it from a figure in a document to a figure somebody stands behind.
            rows.add(new Setting("MINIMUM_DECLARABLE", floor.getValue().toPlainString(),
                    floor.getKey(), Provenance.LEGAL_ADVICE));
        }
        if (rows.isEmpty()) {
            // Nothing may be declared at all in this state, which is a configuration fault rather
            // than a policy. Better said out loud than shown as an empty section.
            rows.add(new Setting("MINIMUM_DECLARABLE", null, null, Provenance.NOT_SET));
        }
        return List.copyOf(rows);
    }

    private List<Setting> exchange() {
        return List.of(new Setting("INQUIRIES_PER_HOUR", String.valueOf(inquiriesPerHour),
                "PER_TENANT_PER_HOUR", Provenance.OPERATIONAL_DEFAULT));
    }

    /**
     * The thresholds the two published models turn on.
     *
     * <p>Compiled constants rather than configuration, and shown anyway. A bank's reviewer asking
     * what "elevated" means, or what confidence puts a pair of records in front of a person, is
     * asking about the product rather than about the deployment — and a number they cannot see is
     * a number they have to take on trust.
     */
    private List<Setting> models() {
        return List.of(
                new Setting("RISK_MODEL_VERSION", RiskIndicatorService.MODEL_VERSION,
                        null, Provenance.COMPILED),
                new Setting("MATCH_REVIEW_THRESHOLD",
                        String.valueOf(MatchAssessment.REVIEW_THRESHOLD),
                        "CONFIDENCE", Provenance.COMPILED),
                new Setting("MATCH_STRONG_CONFIDENCE",
                        String.valueOf(MatchAssessment.STRONG_CONFIDENCE),
                        "CONFIDENCE", Provenance.COMPILED));
    }

    /**
     * @param retention how long records are kept, and when the sweep runs
     * @param rights    how long there is to answer somebody asking about themselves
     * @param reporting the floor per currency; a currency absent from this list is refused
     * @param exchange  limits on asking
     * @param models    thresholds the risk indicator and the match scorer turn on
     */
    public record Settings(List<Setting> retention, List<Setting> rights,
                           List<Setting> reporting, List<Setting> exchange,
                           List<Setting> models) {
    }

    /**
     * @param key        what the setting is, as a code the screen turns into words in either
     *                   language
     * @param value      the effective value, or null when nothing is configured — which for the
     *                   reporting floor is itself the answer
     * @param unit       years, days, a currency code, or null where the value speaks for itself
     * @param provenance where it came from, which for three of these is the point
     */
    public record Setting(String key, String value, String unit, Provenance provenance) {
    }

    /**
     * Where a value came from, and how much weight it will bear.
     *
     * <p>Four states rather than "configured / not configured", because the difference between a
     * figure somebody decided and a figure nobody has looked at is invisible once both are
     * rendered as a number on a page.
     */
    public enum Provenance {
        /** From the March 2026 terms of reference. */
        TERMS_OF_REFERENCE,

        /**
         * Advised by counsel, August 2026.
         *
         * <p>The strongest provenance on this screen and still not a clearance. It means a lawyer
         * was asked the question and answered it, which is different from a regulator having
         * approved anything — counsel's own note on prior authorisation is hedged ("j'estime"),
         * and that hedge belongs to every value in this state.
         */
        LEGAL_ADVICE,

        /**
         * A plausible number nobody has checked against the law.
         *
         * <p>The dangerous kind, and the reason this screen exists. Two of the three retention
         * periods left this state in August 2026; the one nobody asked about did not, and it is
         * now the only value on the screen wearing this label — which makes it far easier to see
         * than it was when it had company.
         */
        UNVERIFIED_PLACEHOLDER,

        /** Chosen for the deployment to run sensibly; no legal weight either way. */
        OPERATIONAL_DEFAULT,

        /** A constant in a published model, changed by a release rather than by configuration. */
        COMPILED,

        /** Nothing is configured, and the consequence is stated rather than implied. */
        NOT_SET
    }
}
