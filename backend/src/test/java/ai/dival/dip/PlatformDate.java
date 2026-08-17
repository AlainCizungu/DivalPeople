package ai.dival.dip;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Today, as the application sees it.
 *
 * <p><strong>{@code LocalDate.now()} in a test is a bug waiting for the right hour.</strong> The
 * platform's clock is {@code Clock.systemUTC()} — see {@code TimeConfig} — so every service that
 * asks what day it is asks UTC. A test that calls the no-argument {@code LocalDate.now()} asks the
 * <em>JVM's default zone</em> instead, and the two disagree for part of every day.
 *
 * <p>That disagreement found us. {@code DeclarationTest.futureDefaultDateIsRefused} declares an
 * obligation dated {@code today + 1} and expects a refusal, because a future default date starts
 * the retention clock in the future and keeps a record alive past the period the law allows. Run
 * from a machine behind UTC, in the window between local midnight and UTC midnight, the test's
 * "tomorrow" is the service's "today" — {@code isAfter} is false, nothing is refused, and the test
 * fails. Run an hour later it passes. Nothing about the code changed.
 *
 * <p>This is the third time this project has been bitten by a zone that was assumed rather than
 * stated: {@code date_trunc} truncating in the session zone rather than UTC, five TIX paths reading
 * the machine clock instead of the injected one, and now the tests themselves. The pattern is
 * always the same — the wrong answer is only wrong for a few hours a day, so it survives every
 * review and every green build until somebody runs it at the wrong moment.
 *
 * <p>A static helper rather than an injected {@code Clock} because the fixtures that need it are
 * static factory methods, which cannot reach an autowired field. It is deliberately not
 * configurable: its whole job is to be the same zone the application uses, and a knob would let
 * the two drift apart again.
 */
public final class PlatformDate {

    private PlatformDate() {
    }

    /** The date the platform's services would compute right now. */
    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
