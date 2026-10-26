plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.aicommit"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.2")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else goland("2026.2.3")
        bundledPlugin("Git4Idea")
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
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.aicommit.CoreTest"
    enableAssertions = true
}
tasks.check { dependsOn("coreTest") }
