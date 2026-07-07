plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.plugin.publish) // Also applies java-gradle-plugin (plugin descriptor + validatePlugins).
}

group = "io.github.ahmetsirim" // Published coordinates: group:name:version; name comes from rootProject.name.
version = "0.1.0-SNAPSHOT"

repositories {
    google() // AGP's gradle-api lives on Google's Maven repository.
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Same mechanics as an Android library: implementation dependencies travel with the artifact.
    // The difference is the destination. A library's dependencies land in the consuming app's
    // runtime; a plugin's dependencies land on the consuming build's classpath, shared with every
    // other plugin of that build (why version conflicts are a plugin author's classic gotcha).
    implementation(libs.asm)

    // Compiled against, never bundled: the consumer's own AGP provides these classes at runtime.
    // Pinned to the lowest AGP whose Variants API we rely on, so newer API never sneaks in.
    compileOnly(libs.agp.api)
}

// Single source for two outputs: the plugin descriptor (how Gradle maps the applied id to our
// implementation class) and the Plugin Portal listing (everything a visitor sees on the plugin page).
gradlePlugin {
    website = "https://github.com/AhmetSIRIM/kot" // Rendered as links on the Portal page.
    vcsUrl = "https://github.com/AhmetSIRIM/kot"
    plugins {
        create("kot") {
            id = "io.github.ahmetsirim.kot" // What consumers write in plugins {}; also the Portal URL slug.
            implementationClass = "io.github.ahmetsirim.kot.KotPlugin" // The entry point Gradle instantiates on apply.
            displayName = "kot" // Portal listing title and summary.
            description = "Verifies a built AAR's emitted consumer floor (Kotlin metadata version, minCompileSdk, minAndroidGradlePluginVersion, bytecode major) against the declared floors."
            tags = listOf("kotlin-metadata", "aar", "android", "compatibility", "verification", "library") // Portal search keywords.
        }
    }
}

// Creates a third source set next to the built-in main and test (Android has the same concept
// under the hood: src/debug, src/release and src/androidTest are all source sets, created for
// you by AGP). This one holds TestKit tests that launch real Gradle builds, so it plays the
// androidTest role; src/test stays the plain unit layer.
val functionalTest: SourceSet by sourceSets.creating

// Makes pluginUnderTestMetadata write the plugin's classpath to a file at build time;
// GradleRunner.withPluginClasspath() reads that file to inject the unpublished plugin into test builds.
gradlePlugin.testSourceSets(functionalTest)

// Creating the source set above also created its dependency buckets (functionalTestImplementation
// and friends), but after the point where the Kotlin DSL generates typed accessors; these
// delegates pull the existing configurations into scope by the property's own name.
val functionalTestImplementation: Configuration by configurations.getting
val functionalTestRuntimeOnly: Configuration by configurations.getting

dependencies {
    functionalTestImplementation(gradleTestKit()) // Provides GradleRunner, the entry point of every functional test.

    functionalTestImplementation(libs.junit.jupiter)
    functionalTestImplementation(libs.kotest.assertions.core)
    functionalTestRuntimeOnly(libs.junit.platform.launcher) // Gradle does not put the launcher on the runtime classpath itself.

    functionalTestImplementation(libs.asm) // AarFixture synthesizes the fixture AAR's class files with it.
}

// A source set does not run by itself; this registers an instance of the existing Test task type
// (not a custom task class) to give functionalTest a runner.
val functionalTestTask: TaskProvider<Test> = tasks.register<Test>("functionalTest") {
    group = "verification" // Placement in the ./gradlew tasks listing.
    description = "Runs the Gradle TestKit functional tests."
    testClassesDirs = functionalTest.output.classesDirs // Which compiled classes count as tests.
    classpath = functionalTest.runtimeClasspath // What those tests see at runtime.
    useJUnitPlatform()
}

// check is a lifecycle umbrella (like build and assemble): it does no work itself, it only runs
// whatever hangs on it. This is the line that makes ./gradlew check (and later CI) run functionalTest.
tasks.check {
    dependsOn(functionalTestTask)
}

tasks.test {
    useJUnitPlatform() // The built-in runner for src/test; the JVM sibling of testDebugUnitTest on Android.
}
