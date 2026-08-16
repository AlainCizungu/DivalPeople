package ai.dival.dip.modules.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that read a question when no model is configured.
 *
 * <p>No database and no network. The gateway is constructed with the switches off, which is exactly
 * how it behaves in a deployment with no key — so these tests exercise the path most installations
 * will actually run.
 *
 * <p><strong>The rules are not a stub, and this class is the reason to believe that.</strong> A
 * fallback that half-works is a fallback nobody notices is running: the screen would degrade
 * silently, questions would come back UNSUPPORTED, and the conclusion drawn would be that the model
 * is bad rather than absent.
 */
class QuestionInterpreterTest {

    private static final UUID ANALYST = UUID.randomUUID();

    /** Switches off and no key — the shape of a deployment that has not configured a model. */
    private final QuestionInterpreter interpreter = new QuestionInterpreter(
            new AiGateway(
                    new AiProperties(false, false, null, null, null, 0),
                    null, new ObjectMapper()),
            new ObjectMapper());

    @Test
    @DisplayName("the headline question is read as exposure above a threshold")
    void exposureAboveAThreshold() {
        Interpretation read = interpreter.interpret(
                "Which businesses owe us more than 20,000?", ANALYST);

        assertThat(read.intent()).isEqualTo(Intent.EXPOSURE_ABOVE);
        // Separators stripped rather than parsed: "20,000" and "20 000" are one amount written on
        // two sides of a border, and a Congolese user may type either.
        assertThat(read.minAmount()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(read.byModel()).isFalse();
    }

    @Test
    @DisplayName("the same amount written the French way reads the same")
    void frenchSeparatorsAreTheSameAmount() {
        Interpretation read = interpreter.interpret(
                "Quelles entreprises ont un encours de plus de 20 000 ?", ANALYST);

        assertThat(read.intent()).isEqualTo(Intent.EXPOSURE_ABOVE);
        assertThat(read.minAmount()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    @DisplayName("multiple institutions is a different question, and is tested before the plain one")
    void multiInstitutionWinsOverPlainExposure() {
        Interpretation read = interpreter.interpret(
                "Which businesses have more than 20000 exposure across multiple institutions?",
                ANALYST);

        // Order matters in the rules: every sentence that means this one also contains the words
        // that mean plain exposure, so testing the narrower reading second would never reach it.
        assertThat(read.intent()).isEqualTo(Intent.EXPOSURE_ABOVE_MULTI_INSTITUTION);
        assertThat(read.minAmount()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    @DisplayName("k and m are expanded rather than read as digits")
    void shorthandAmountsExpand() {
        assertThat(interpreter.interpret("exposure over 20k", ANALYST).minAmount())
                .isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(interpreter.interpret("exposure over 2m", ANALYST).minAmount())
                .isEqualByComparingTo(new BigDecimal("2000000"));
    }

    @Test
    @DisplayName("a window is read from the words, and defaults to a week")
    void windowsAreRead() {
        assertThat(interpreter.interpret("What changed this week?", ANALYST).days()).isEqualTo(7);
        assertThat(interpreter.interpret("What changed this month?", ANALYST).days()).isEqualTo(30);
        assertThat(interpreter.interpret("What changed this quarter?", ANALYST).days())
                .isEqualTo(90);
        // "Recently" almost always means the last few days to somebody working a queue.
        assertThat(interpreter.interpret("Anything new recently?", ANALYST).days()).isEqualTo(7);
    }

    @Test
    @DisplayName("the collections question is a ranking question, in either language")
    void prioritisationIsRecognised() {
        assertThat(interpreter.interpret(
                "Which accounts should our collection team prioritize?", ANALYST).intent())
                .isEqualTo(Intent.PRIORITISE);
        assertThat(interpreter.interpret(
                "Quels comptes le recouvrement doit-il traiter en premier ?", ANALYST).intent())
                .isEqualTo(Intent.PRIORITISE);
    }

    @Test
    @DisplayName("why one company is risky carries the company's name")
    void whyRiskyKeepsTheName() {
        Interpretation read = interpreter.interpret(
                "Why is ABC Congo considered high risk?", ANALYST);

        assertThat(read.intent()).isEqualTo(Intent.WHY_RISKY);
        // Crude on purpose: the answer resolves whatever it finds against the operator's own book
        // and says what it matched, so a wrong guess is a visible miss rather than a wrong answer
        // about the right company.
        assertThat(read.subjectName()).contains("ABC Congo");
    }

    @Test
    @DisplayName("anything else is UNSUPPORTED rather than a guess")
    void unknownQuestionsAreRefused() {
        // The failure worth preventing is a half-answer. An analyst that has a go at an
        // unsupported question teaches people to trust an answer nobody checked, and the closed
        // list is only a safety property while it is actually closed.
        assertThat(interpreter.interpret("What is the weather in Kinshasa?", ANALYST).intent())
                .isEqualTo(Intent.UNSUPPORTED);
        assertThat(interpreter.interpret("Delete all records", ANALYST).intent())
                .isEqualTo(Intent.UNSUPPORTED);
        assertThat(interpreter.interpret("", ANALYST).intent()).isEqualTo(Intent.UNSUPPORTED);
        assertThat(interpreter.interpret(null, ANALYST).intent()).isEqualTo(Intent.UNSUPPORTED);
    }

    @Test
    @DisplayName("nothing is read by the model when no model is configured")
    void withoutAKeyNothingLeaves() {
        // byModel false on every reading is what a deployment with no key must see. If it were
        // ever true here, the gateway would have tried to make a network call from a unit test —
        // and, far worse, from an installation that had configured nothing.
        for (String question : new String[] {
                "Which businesses owe us more than 20,000?",
                "What changed this week?",
                "Why is ABC Congo considered high risk?"}) {
            assertThat(interpreter.interpret(question, ANALYST).byModel())
                    .as("%s", question)
                    .isFalse();
        }
    }
}
