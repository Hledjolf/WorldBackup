// WorldBackup: a game-agnostic core plus one adapter per game.
// core/ is plain Java at --release 17 (the level translated to C#, C++ and Lua); adapters/hytale/ is the Hytale
// plugin on Java 25, which merges core into its single jar.
plugins { base }

// The dev build is <releaseVersion>-dev. `gradlew release` builds <releaseVersion> itself (see "release" below).
val releaseVersion = "1.0.0"
version = if (hasProperty("releaseBuild")) releaseVersion else "$releaseVersion-dev"

val hytaleServerJar = (findProperty("hytaleServerJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"
extra["hytaleServerJar"] = hytaleServerJar

subprojects {
    version = rootProject.version
    apply(plugin = "java")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")
    }

    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
    tasks.withType<Test> { useJUnitPlatform() }
}

val hytaleJar = project(":adapters:hytale").tasks.named<Jar>("jar")

// ---------------------------------------------------------------------------------------------------------
// checkAdapterBoundary: core/ is game-agnostic. No file under core/ may name a game API. Adapters may.
// ---------------------------------------------------------------------------------------------------------
val checkAdapterBoundary = tasks.register("checkAdapterBoundary") {
    group = "verification"
    description = "Fails if any file under core/ uses a game API."
    val sources = fileTree("core") { include("**/*.java"); exclude("build/**") }
    inputs.files(sources)
    doLast {
        val offenders = sources.files.filter { f -> f.readLines().any { it.contains("com.hypixel") } }
        if (offenders.isNotEmpty()) {
            throw GradleException("Game API used in core:\n" + offenders.joinToString("\n") { " - " + it.relativeTo(projectDir) })
        }
    }
}

// The plugin jar must never carry Hytale classes, the probe, or source files.
val checkJarContents = tasks.register("checkJarContents") {
    group = "verification"
    description = "Fails if the plugin jar contains anything outside the allow list."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    doLast {
        val allowed = Regex("""META-INF/.*|manifest\.json|LICENSE|worldbackup/.*\.class|Settings/Hledjolf_WorldBackup\.settings\.json|Common/UI/Custom/WorldBackup/[A-Za-z]+\.ui|playtest/.*\.md""")
        val names = java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        val bad = names.filter { !allowed.matches(it) }
        if (bad.isNotEmpty()) throw GradleException("Unexpected files in the jar:\n" + bad.joinToString("\n") { " - $it" })
        listOf("worldbackup/WorldBackupPlugin.class", "worldbackup/core/FileListing.class", "worldbackup/cli/Main.class",
            "Settings/Hledjolf_WorldBackup.settings.json", "LICENSE")
            .filter { it !in names }.forEach { throw GradleException("The jar is missing $it") }
    }
}

tasks.named("check") {
    dependsOn(project(":core").tasks.named("check"))
    dependsOn(project(":adapters:hytale").tasks.named("check"))
    dependsOn(checkAdapterBoundary)
    dependsOn(checkJarContents)
}

// ---------------------------------------------------------------------------------------------------------
// release (as CommandDeck's): `gradlew release` runs the whole build again as version <releaseVersion> (no
// -dev), runs check on it, verifies the jar, and copies the upload files into release/<version>/: the jar
// (CurseForge) and a zip with the jar, README.md, LICENSE and CHANGELOG.md (Nexus). The release jar has no
// playtest plans. The normal build stays <releaseVersion>-dev.
// ---------------------------------------------------------------------------------------------------------
tasks.register<GradleBuild>("release") {
    group = "distribution"
    description = "Builds and checks version $releaseVersion and copies the jar and the zip to release/$releaseVersion/."
    dir = projectDir
    tasks = listOf("check", "releaseFiles")
    startParameter.projectProperties = gradle.startParameter.projectProperties + mapOf("releaseBuild" to "true")
}

val releaseDir = layout.projectDirectory.dir("release/$releaseVersion")

// checkReleaseJar: the jar says the release version, carries the license, and has no development files.
// The core's classes are Java 17 (major 61); the adapter's are the game's Java 25 (69).
val checkReleaseJar = tasks.register("checkReleaseJar") {
    group = "verification"
    description = "Fails if the release jar has the wrong version, no LICENSE, development files or the wrong bytecode level."
    dependsOn(hytaleJar, checkJarContents)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    val expected = version.toString()
    doLast {
        if (expected.endsWith("-dev")) throw GradleException("checkReleaseJar runs only in a release build (gradlew release)")
        java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            val names = zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            if ("LICENSE" !in names) throw GradleException("The release jar has no LICENSE")
            val extra = names.filter { it.startsWith("playtest/") || it.startsWith("fixture") }
            if (extra.isNotEmpty()) throw GradleException("The release jar has development files:\n" + extra.joinToString("\n") { " - $it" })
            val manifest = zf.getInputStream(zf.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
            if (!Regex(""""Version"\s*:\s*"${Regex.escape(expected)}"""").containsMatchIn(manifest)) {
                throw GradleException("manifest.json does not say Version $expected")
            }
            val coreDirs = listOf("worldbackup/core/", "worldbackup/app/", "worldbackup/ui/", "worldbackup/cli/")
            names.filter { it.endsWith(".class") }.forEach { n ->
                val b = zf.getInputStream(zf.getEntry(n)).use { it.readNBytes(8) }
                val major = ((b[6].toInt() and 0xff) shl 8) or (b[7].toInt() and 0xff)
                val want = if (coreDirs.any { n.startsWith(it) }) 61 else 69
                if (major != want) throw GradleException("$n is class file version $major, expected $want")
            }
        }
    }
}

val releaseZip = tasks.register<Zip>("releaseZip") {
    dependsOn(checkReleaseJar)
    archiveFileName.set("WorldBackup-$version.zip")
    destinationDirectory.set(releaseDir)
    from(hytaleJar)
    from(layout.projectDirectory) { include("README.md", "LICENSE", "CHANGELOG.md") }
}

tasks.register<Copy>("releaseFiles") {
    group = "distribution"
    description = "Copies the checked release jar to release/<version>/ and makes the zip. Run through gradlew release."
    dependsOn(checkReleaseJar, releaseZip)
    mustRunAfter("check")
    from(hytaleJar)
    into(releaseDir)
}
