plugins { kotlin("jvm"); id("net.fabricmc.fabric-loom"); id("com.gradleup.shadow") }
repositories { maven("https://maven.shedaniel.me/") }
dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.165.0+26.3")
    implementation("me.shedaniel.cloth:cloth-config-fabric:26.3.159")
    implementation(project(":core"))
}
base { archivesName.set("Suggestor-Fabric") }
tasks.shadowJar {
    archiveFileName.set("Suggestor-Fabric-${project.version}+mc26.3.jar")
    configurations = listOf(project.configurations.runtimeClasspath.get())
    dependencies {
        include(project(":core"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
        include(dependency("org.jetbrains:annotations:.*"))
    }
    from(project(":updater").tasks.named("shadowJar")) { into("META-INF/suggestor"); rename { "updater.jar" } }
}
tasks.processResources { filesMatching("fabric.mod.json") { expand("version" to project.version) } }
tasks.build { dependsOn(tasks.shadowJar) }
loom { mods { create("suggestor") { sourceSet(sourceSets.main.get()) } } }

val fixture by sourceSets.creating {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}
tasks.register<Jar>("fixtureJar") {
    dependsOn(tasks.named(fixture.classesTaskName))
    archiveFileName.set("SuggestorFixture.jar")
    from(fixture.output)
}
