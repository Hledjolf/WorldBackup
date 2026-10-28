// The Hytale plugin (Java 25). The server jar is the API: compile-only, never bundled.
// The game-agnostic core is merged into the plugin jar, so server owners install a single file.
val hytaleServerJar = rootProject.extra["hytaleServerJar"] as String

dependencies {
    "implementation"(project(":core"))
    "compileOnly"(files(hytaleServerJar))
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", rootProject.version.toString())
    filesMatching("manifest.json") { expand("version" to rootProject.version.toString()) }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("WorldBackup")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("mods"))
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    from(rootProject.file("LICENSE"))
    // playtest plans are for PlaytestGamified sessions; the published jar leaves them out
    if (rootProject.hasProperty("releaseBuild")) exclude("playtest/**")
    // the same jar is the command line: java -jar WorldBackup-<ver>.jar <command>
    manifest { attributes("Main-Class" to "worldbackup.cli.Main") }
}
