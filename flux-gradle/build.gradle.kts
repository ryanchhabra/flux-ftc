plugins {
    `maven-publish`
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "dev.ryanchhab"
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
            id = "dev.ryanchhab.flux"
            implementationClass = "dev.ryanchhab.flux.gradle.FluxPlugin"
            displayName = "FTC Flux"
            description = "Hot-code-reload deploy pipeline for FIRST Tech Challenge TeamCode."
            tags = listOf("ftc", "android", "hot-reload", "robotics")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Plugin Portal publishing needs these; harmless locally, required before a real release.
// See docs/design/distribution.md.
gradlePlugin {
    website = "https://github.com/ryanchhabra/flux-ftc"
    vcsUrl = "https://github.com/ryanchhabra/flux-ftc"
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "FTC Flux Gradle Plugin"
            description = "Compile, dex, push and hot-reload FTC TeamCode in well under a second."
            url = "https://github.com/ryanchhabra/flux-ftc"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
                }
            }
            developers {
                developer {
                    id = "ryanchhabra"
                    name = "Ryan Chhabra"
                }
            }
        }
    }
    repositories {
        // See flux-runtime/build.gradle for the rationale, and docs/design/distribution.md.
        maven {
            name = "repoFolder"
            url = uri("$rootDir/../maven")
        }
    }
}
