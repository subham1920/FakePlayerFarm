plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("com.gradleup.shadow") version "9.4.3"
}

group = "com.plugin"
version = "1.0.3"
description = "AFK Dummy - Spawn fake players for chunk loading and farm mechanics"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.bstats:bstats-bukkit:3.2.1")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "description" to (project.description ?: ""))
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.bstats", "com.plugin.afkdummy.bstats")
    minimize()
}

tasks.test {
    useJUnitPlatform()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
