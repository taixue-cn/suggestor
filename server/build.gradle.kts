plugins { kotlin("jvm"); id("com.gradleup.shadow") }
repositories { maven("https://repo.papermc.io/repository/maven-public/") }
dependencies { implementation(project(":core")); compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable") }
tasks.shadowJar {
    archiveFileName.set("Suggestor-Paper-${project.version}+mc26.3.jar")
    relocate("com.google.gson", "cc.taixue.suggestor.internal.gson")
}
tasks.build { dependsOn(tasks.shadowJar) }
