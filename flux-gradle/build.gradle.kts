plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "dev.flux"
version = "0.1.0-alpha"

repositories {
    google()
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Android Gradle Plugin API surface only — never bundled, never applied by us.
    // The consuming project supplies the real AGP implementation at runtime; we only
    // need the public Variant API types (AndroidComponentsExtension, ApplicationVariant, …)
    // to compile against.
    compileOnly("com.android.tools.build:gradle:8.7.2")

    // D8 is invoked directly (com.android.tools.r8.D8) rather than through AGP's own dex
    // task, so it must be on the plugin's runtime classpath, not compileOnly.
    implementation("com.android.tools:r8:9.4.17")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("fluxLoad") {
            id = "dev.flux.load"
            implementationClass = "dev.flux.gradle.FluxPlugin"
            displayName = "FTC Flux"
            description = "Hot-code-reload deploy pipeline for FIRST Tech Challenge TeamCode."
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
