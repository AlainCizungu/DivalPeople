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
}
