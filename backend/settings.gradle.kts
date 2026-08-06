plugins {
    // Resolves the Java 21 toolchain automatically if no JDK 21 is installed locally,
    // so a contributor whose default JDK is a different version still gets a correct build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "dip-backend"
