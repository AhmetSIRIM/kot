plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.plugin.publish) // Also applies java-gradle-plugin.
    alias(libs.plugins.detekt)
}

group = "io.github.ahmetsirim"
// Tag-driven at release: the publish pipeline passes VERSION_NAME derived from the git tag;
// local builds fall back to the development version.
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.0-SNAPSHOT")

repositories {
    google() // AGP's gradle-api lives on Google's Maven repository.
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.asm)

    // Compiled against, never bundled: the consumer's own AGP provides these classes at runtime.
    // Pinned to the lowest AGP whose Variants API we rely on, so newer API never sneaks in.
    compileOnly(libs.agp.api)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
}

gradlePlugin {
    website = "https://github.com/AhmetSIRIM/kot"
    vcsUrl = "https://github.com/AhmetSIRIM/kot"
    plugins {
        create("kot") {
            id = "io.github.ahmetsirim.kot"
            implementationClass = "io.github.ahmetsirim.kot.KotPlugin"
            displayName = "kot"
            description = "Verifies a built AAR's emitted consumer floor (Kotlin metadata version, minCompileSdk, minAndroidGradlePluginVersion, bytecode major) against the declared floors."
            tags = listOf("kotlin-metadata", "aar", "android", "compatibility", "verification", "library")
        }
    }
}

// Holds the TestKit tests, which launch real Gradle builds; src/test holds the plain JVM unit tests.
val functionalTest: SourceSet = sourceSets.create("functionalTest")

// Lets GradleRunner.withPluginClasspath() inject the unpublished plugin into the test builds.
gradlePlugin.testSourceSets(functionalTest)

// The source set is created after the Kotlin DSL generated its typed accessors, so its
// configurations are looked up by name.
val functionalTestImplementation: Configuration = configurations.getByName("functionalTestImplementation")
val functionalTestRuntimeOnly: Configuration = configurations.getByName("functionalTestRuntimeOnly")

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher) // Gradle does not put the launcher on the runtime classpath itself.

    functionalTestImplementation(gradleTestKit())

    functionalTestImplementation(libs.junit.jupiter)
    functionalTestImplementation(libs.kotest.assertions.core)
    functionalTestRuntimeOnly(libs.junit.platform.launcher)

    functionalTestImplementation(libs.asm) // AarFixture synthesizes the fixture AAR's class files with it.
}

tasks.test {
    useJUnitPlatform()
}

val functionalTestTask: TaskProvider<Test> = tasks.register<Test>("functionalTest") {
    group = "verification"
    description = "Runs the Gradle TestKit functional tests."
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.check {
    dependsOn(functionalTestTask)
}

// The body of the GitHub Release: the CHANGELOG.md section of the version being built. Fails when
// the changelog has no section for that version, so a release cannot go out without its entry.
tasks.register("releaseNotes") {
    group = "publishing"
    description = "Writes the changelog section of the current version to build/release-notes.md."

    val changelog: RegularFile = layout.projectDirectory.file("CHANGELOG.md")
    val releaseNotes: Provider<RegularFile> = layout.buildDirectory.file("release-notes.md")
    val versionName: String = project.version.toString()
    inputs.file(changelog)
    inputs.property("versionName", versionName)
    outputs.file(releaseNotes)

    doLast {
        val lines: List<String> = changelog.asFile.readLines()
        val headingIndex: Int = lines.indexOfFirst { line: String -> line.startsWith("## [$versionName]") }
        if (headingIndex < 0) throw GradleException("CHANGELOG.md has no section for version $versionName.")

        // A section ends at the next version heading or at the link references that close the file.
        val linkReference = Regex("""^\[[^\]]+]: .*""")
        val section: String = lines
            .drop(headingIndex + 1)
            .takeWhile { line: String -> !line.startsWith("## [") && !linkReference.matches(line) }
            .joinToString(separator = "\n")
            .trim()
        if (section.isEmpty()) throw GradleException("The CHANGELOG.md section for version $versionName is empty.")

        releaseNotes.get().asFile.writeText(section + "\n")
    }
}
