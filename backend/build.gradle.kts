// Imported rather than written as java.net.ServerSocket below, because inside the Kotlin DSL
// `java` resolves to the Java plugin's extension and shadows the package of the same name — so a
// fully-qualified reference reads as a property access and fails to compile.
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

plugins {
    java
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.dival"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }

    // ---------------------------------------------------------------------
    // A green build that ran almost nothing must not look like a green build.
    //
    // Everything touching the database is @RequiresDocker and skips when no container runtime is
    // available, so a developer without Docker still gets useful signal from the unit tests. That
    // is deliberate and stays. What was missing is that it said nothing: a run where 85% of the
    // suite never executed printed BUILD SUCCESSFUL, and the account-reference work was reported
    // as passing when not one of its tests had run.
    //
    // Locally this warns, because the developer chose to run without Docker and knows it.
    // On CI it fails, because there Docker is always present — so a skip means the container
    // runtime broke, and a build that quietly stops testing the database is worse than a red one.
    // ---------------------------------------------------------------------
    // Read off the root suite rather than counted per test: one callback, two numbers Gradle
    // has already totalled, and nothing to get wrong in the arithmetic.
    var skipped = 0L
    var total = 0L

    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, result ->
        if (suite.parent == null) {
            skipped = result.skippedTestCount
            total = result.testCount
        }
    }))

    doLast {
        if (skipped == 0L) return@doLast

        val message = "$skipped of $total tests were SKIPPED. " +
                "Almost all of them need Docker; without it nothing that touches the database ran."

        if (System.getenv("CI") != null) {
            throw GradleException(
                "$message\n" +
                        "    On CI this is a failure rather than a warning: Docker is always " +
                        "present here, so a skip means the container runtime is broken and this " +
                        "build proved far less than it appears to.")
        }
        logger.warn("")
        logger.warn("=".repeat(78))
        logger.warn("  WARNING: $message")
        logger.warn("  Start Docker and run again before trusting this result.")
        logger.warn("=".repeat(78))
        logger.warn("")
    }
}

// `./gradlew bootRun` is only ever local development, so activate that profile by default
// rather than making every developer remember an environment variable.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active", "local"))

    // ---------------------------------------------------------------------
    // Refuse to start when something else already holds the port.
    //
    // Spring says "Port 8080 was already in use" and stops, which sounds like the whole story and
    // is the least useful half of it. The application exits; the squatter keeps answering. Every
    // screen then works, against whatever code that process started with — so a controller added
    // this afternoon returns 404 from an API that authenticates correctly and looks entirely
    // healthy. Three afternoons have gone to that here, the last one to a JVM that had been
    // running for a day and eighteen hours.
    //
    // The check itself has existed in infra/dev.sh for two of those three, and never helped: the
    // natural move after a failed bootRun is another bootRun, not a script nobody thinks to run
    // at that moment. So it belongs where the failure happens.
    //
    // This does not close a race — something can take the port between the probe and the bind.
    // It is not trying to. It turns the common case from a five-word message into the name, age
    // and process id of whatever is squatting, and the command to stop it.
    // ---------------------------------------------------------------------
    doFirst {
        val port = (System.getProperty("server.port") ?: "8080").toInt()
        val free = try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (refused: IOException) {
            false
        }

        if (!free) {
            // Best effort, and quiet when it fails: this runs on whatever machine a developer
            // has, and a missing lsof must not turn a helpful message into a build script crash.
            fun probe(vararg command: String): String = try {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().readText().trim()
                    .also { process.waitFor() }
            } catch (unavailable: Exception) {
                ""
            }

            val pid = probe("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t")
                .lineSequence().firstOrNull().orEmpty()
            val who = if (pid.isBlank()) "" else {
                val name = probe("ps", "-o", "comm=", "-p", pid)
                val age = probe("ps", "-o", "etime=", "-p", pid)
                "\n  Held by process $pid ($name), up ${age.ifBlank { "unknown" }}."
            }

            throw GradleException(
                "Port $port is already in use, so this application would exit while whatever " +
                    "holds it kept answering — and every screen would work against code of " +
                    "whatever age that process started with.$who\n" +
                    (if (pid.isBlank()) "  Find it:  lsof -nP -iTCP:$port -sTCP:LISTEN\n"
                     else "  Stop it:  kill $pid\n") +
                    "  Or:       ./infra/dev.sh port",
            )
        }
    }
}
