package ai.dival.dip.modules.settings;

import ai.dival.dip.modules.resolution.MatchAssessment;
import ai.dival.dip.modules.risk.RiskIndicatorService;
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
        return new Settings(retention(), reporting(), exchange(), models());
    }

    /**
     * How long somebody stays in the registry.
     *
     * <p>The section that earns this whole screen. These are legal periods, the values are the
     * TDR's illustrative ones, and nobody has checked them against the Code.
     */
    private List<Setting> retention() {
        TixProperties.Retention periods = tix.retention();
        return List.of(
                new Setting("RETENTION_SIMPLE_YEARS", String.valueOf(periods.simpleYears()),
                        "YEARS", Provenance.UNVERIFIED_PLACEHOLDER),
                new Setting("RETENTION_REPEAT_YEARS", String.valueOf(periods.repeatYears()),
                        "YEARS", Provenance.UNVERIFIED_PLACEHOLDER),
                new Setting("RETENTION_SETTLED_DAYS", String.valueOf(periods.settledDays()),
                        "DAYS", Provenance.UNVERIFIED_PLACEHOLDER),
                new Setting("RETENTION_PURGE_SCHEDULE", retentionPurgeCron,
                        "CRON", Provenance.OPERATIONAL_DEFAULT));
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
            rows.add(new Setting("MINIMUM_DECLARABLE", floor.getValue().toPlainString(),
                    floor.getKey(), Provenance.TERMS_OF_REFERENCE));
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
     * @param reporting the floor per currency; a currency absent from this list is refused
     * @param exchange  limits on asking
     * @param models    thresholds the risk indicator and the match scorer turn on
     */
    public record Settings(List<Setting> retention, List<Setting> reporting,
                           List<Setting> exchange, List<Setting> models) {
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
         * A plausible number nobody has checked against the law.
         *
         * <p>The dangerous kind, and the reason this screen exists. All three retention periods
         * are in this state.
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
