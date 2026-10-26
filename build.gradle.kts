plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.aicommit"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.3")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else goland("2026.2.3")
        bundledPlugin("Git4Idea")
        // Commit patch helpers and Git repository base classes live in separate platform modules.
        bundledModules(
            "intellij.platform.vcs.impl",
            "intellij.platform.vcs.dvcs",
            "intellij.platform.vcs.dvcs.impl",
        )
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion { sinceBuild = "262"; untilBuild = "262.*" }
    }
}
tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses)
    // The standalone runner needs IDE-provided libraries (such as Gson) at runtime.
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass = "dev.aicommit.CoreTest"
    enableAssertions = true
}
tasks.check { dependsOn("coreTest") }
// CoreTest is an assertion-based main program, not a JUnit test suite.
tasks.test { failOnNoDiscoveredTests = false }
