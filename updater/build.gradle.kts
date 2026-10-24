plugins { kotlin("jvm"); id("com.gradleup.shadow") }
dependencies { implementation(project(":core")) }
tasks.shadowJar {
    archiveFileName.set("suggestor-updater.jar")
    manifest { attributes["Main-Class"] = "cc.taixue.suggestor.updater.Updater" }
}
tasks.build { dependsOn(tasks.shadowJar) }
tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("suggestor.updaterJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}
