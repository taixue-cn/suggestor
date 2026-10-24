plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("net.fabricmc.fabric-loom") version "1.17.21" apply false
    id("com.gradleup.shadow") version "9.3.1" apply false
}
allprojects { group = "cc.taixue.suggestor"; version = "0.1.0" }
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(25) }
    extensions.configure<JavaPluginExtension> { withSourcesJar() }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
