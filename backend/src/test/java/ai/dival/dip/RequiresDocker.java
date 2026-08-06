package ai.dival.dip;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Marks a test class that needs a container runtime, so it is skipped rather than failed when
 * Docker is not running.
 *
 * <p>Declared {@code @Inherited} deliberately: JUnit's own condition annotations are not reliably
 * inherited through an abstract base class, which lets the base class's static initialization run
 * before the condition is ever evaluated. Owning the annotation lets us guarantee the behaviour.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@EnabledIf("ai.dival.dip.DockerAvailable#isAvailable")
public @interface RequiresDocker {
}
