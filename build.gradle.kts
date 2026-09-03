import space.minecraftstl.xyml.gradle.docs.UpdateDocuments
import space.minecraftstl.xyml.gradle.cache.RunLibraryCache
import space.minecraftstl.xyml.gradle.ci.GitHubActionUtils
import space.minecraftstl.xyml.gradle.ci.JenkinsUtils
import space.minecraftstl.xyml.gradle.l10n.ParseLanguageSubtagRegistry
import space.minecraftstl.xyml.gradle.pack.ReleaseType
import space.minecraftstl.xyml.gradle.pack.ReleaseVersionResolver
import space.minecraftstl.xyml.gradle.pack.GitBranchGradleTask
import space.minecraftstl.xyml.gradle.pack.GitVersionResolver
import space.minecraftstl.xyml.gradle.utils.PropertiesUtils
import java.util.Properties
import org.gradle.jvm.tasks.Jar

plugins {
    id("checkstyle")
    id("org.glavo.gradle-wrapper-neo") version "0.2.0"
}

group = "space.minecraftstl"
version = "3.0"

val projectConfig = PropertiesUtils.load(file("config/project.properties").toPath())
val isOfficialBuild = JenkinsUtils.IS_ON_CI || GitHubActionUtils.IS_ON_OFFICIAL_REPO
val xymlBranchName = sequenceOf("GITHUB_HEAD_REF", "GITHUB_REF_NAME", "CHANGE_BRANCH", "BRANCH_NAME")
    .mapNotNull { variable -> System.getenv(variable)?.takeIf { it.isNotBlank() } }
    .firstOrNull()
    ?: runCatching {
        providers.exec {
            commandLine("git", "branch", "--show-current")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
val xymlBranchReleaseType = GitVersionResolver.releaseTypeForBranch(xymlBranchName)
val xymlReleaseType = xymlBranchReleaseType ?: ReleaseType.DEV
System.getenv("RELEASE_CHANNEL")?.takeIf { it.isNotBlank() }?.let { suppliedChannel ->
    val suppliedType = ReleaseType.fromName(suppliedChannel)
    require(suppliedType == xymlReleaseType) {
        "RELEASE_CHANNEL=$suppliedChannel does not match Git branch ${xymlBranchName ?: "<detached>"}"
    }
}
val xymlReleaseChannel = xymlReleaseType.getName()
val xymlStableVersion = System.getenv("STABLE_VERSION")?.takeIf { it.isNotBlank() }
    ?: projectConfig.getProperty("stableVersion")
    ?: "1.0.0"
val xymlExplicitReleaseVersion = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
val xymlBuildNumber = System.getenv("BUILD_NUMBER")?.takeIf { it.isNotBlank() }
val xymlReleaseVersion = if (xymlBranchReleaseType != null) {
    if (xymlExplicitReleaseVersion == null
        && xymlBuildNumber == null
        && !isOfficialBuild
        && file(".git").exists()
    ) {
        GitVersionResolver.resolveCurrentReleaseVersion(
            rootDir.toPath(),
            xymlReleaseType,
            xymlStableVersion
        )
    } else {
        ReleaseVersionResolver.resolve(
            xymlReleaseType,
            xymlStableVersion,
            xymlExplicitReleaseVersion,
            xymlBuildNumber,
            isOfficialBuild
        )
    }
} else if (file(".git").exists()) {
    GitVersionResolver.resolveCurrentFeatureVersion(rootDir.toPath(), xymlStableVersion)
} else {
    "$xymlStableVersion.0.0.0.0"
}

extra["xymlReleaseVersion"] = xymlReleaseVersion
extra["xymlReleaseChannel"] = xymlReleaseChannel
extra["xymlBranchName"] = xymlBranchName.orEmpty()

subprojects {
    apply {
        plugin("idea")
    }

    if (path == ":XYMLL") {
        apply {
            plugin("base")
        }
        return@subprojects
    }

    apply {
        plugin("java")
        plugin("maven-publish")
        plugin("checkstyle")
    }

    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    }

    repositories {
        System.getenv("MAVEN_CENTRAL_REPO").let { repo ->
            if (repo.isNullOrBlank())
                mavenCentral()
            else
                maven(url = repo)
        }

        maven(url = "https://jitpack.io")
        maven(url = "https://libraries.minecraft.net")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    @Suppress("UnstableApiUsage")
    tasks.withType<Checkstyle> {
        maxHeapSize.set("2g")

        setConfigProperties("licenseHeaderFile" to rootProject.rootDir.resolve("config/checkstyle/license-header.txt"))
    }

    configure<CheckstyleExtension> {
        sourceSets = setOf()
    }

    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging.showStandardStreams = true
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            mavenLocal()
        }
    }

    tasks.register("checkstyle") {
        dependsOn(tasks["checkstyleMain"], tasks["checkstyleTest"])
    }
}

val xymlWorkflowGroup = "stl"
val nestedBranchBuild = providers.gradleProperty("xyml.branchBuild.nested")
    .map { it.toBooleanStrict() }
    .orElse(false)

val rootBuildResultFile = layout.buildDirectory.file("root-build-result.properties")
val xymlNativeSourceFiles = files(
    layout.projectDirectory.file("libraries/XYMLL/CMakeLists.txt"),
    layout.projectDirectory.file("libraries/XYMLL/XYMLL.ico"),
    fileTree("libraries/XYMLL/XYMLL") {
        include("**/*.cpp", "**/*.h", "**/*.in")
    },
)

fun recordRootBuildResult(artifact: File, version: String, channel: String, branch: String) {
    check(artifact.isFile) {
        "Root :build completed without producing the XYML launcher artifact: $artifact"
    }

    val rootPath = rootDir.toPath().toAbsolutePath().normalize()
    val artifactPath = artifact.toPath().toAbsolutePath().normalize()
    check(artifactPath.startsWith(rootPath)) {
        "XYML launcher artifact is outside the repository root: $artifact"
    }

    val properties = Properties()
    properties.setProperty("task", ":build")
    properties.setProperty("artifact", rootPath.relativize(artifactPath).toString().replace('\\', '/'))
    properties.setProperty("version", version)
    properties.setProperty("channel", channel)
    properties.setProperty("branch", branch)

    val marker = rootBuildResultFile.get().asFile
    marker.parentFile.mkdirs()
    marker.outputStream().use { properties.store(it, "XYML root :build result") }
    logger.lifecycle("XYML root :build result recorded: $artifactPath")
}

fun reusableXYMLLNativeOutput(): Boolean {
    val executable = rootDir.resolve("libraries/XYMLL/build/cmake/Release/XYMLL.exe")
    if (!executable.isFile) {
        return false
    }
    val executableTimestamp = executable.lastModified()
    return xymlNativeSourceFiles.files.all { it.lastModified() <= executableTimestamp }
}

fun registerReleaseBranchBuild(taskName: String, branchName: String, releaseType: ReleaseType) =
    tasks.register<GitBranchGradleTask>(taskName) {
        group = xymlWorkflowGroup
        description = "Builds the local $branchName branch with an inferred ${releaseType.getName()} version."
        this.branchName.set(branchName)
        this.releaseType.set(releaseType)
        gradleArguments.set(listOf(
            "clean",
            "build",
            "-Pxyml.branchBuild.nested=true",
            "--no-daemon",
            "--parallel",
            "--stacktrace"
        ))
        repositoryDirectory.set(layout.projectDirectory)
        artifactDirectory.set(layout.buildDirectory.dir("channel-builds/$branchName"))
    }

registerReleaseBranchBuild("buildMain", "main", ReleaseType.STABLE)
registerReleaseBranchBuild("buildBeta", "beta", ReleaseType.BETA)
registerReleaseBranchBuild("buildAlpha", "alpha", ReleaseType.ALPHA)
registerReleaseBranchBuild("buildDev", "dev", ReleaseType.DEV)
val localBuildTasks = subprojects.map { "${it.path}:build" }
val localCleanTasks = subprojects.map { "${it.path}:clean" }
val runLibraryNames = listOf("xoyz-nbt", "xoyz-mcp")
val runLibraryCacheDirectory = layout.buildDirectory.dir("run-library-cache")

fun promoteRunLibraryCache() {
    val artifacts = runLibraryNames.associateWith { library ->
        project(":$library").tasks.named<Jar>("jar").get().archiveFile.get().asFile.toPath()
    }
    RunLibraryCache.promote(
        runLibraryCacheDirectory.get().asFile.toPath(),
        xymlReleaseVersion,
        artifacts
    )
    logger.lifecycle("XYML run-library cache recorded for {}", runLibraryNames.joinToString(", "))
}

tasks.register<Delete>("clean") {
    group = xymlWorkflowGroup
    description = "Cleans build output for the current checkout without fetching or switching branches."
    dependsOn(localCleanTasks)
    delete(layout.buildDirectory, layout.projectDirectory.dir("buildSrc/build"))
}

val rootBuild = tasks.register("build") {
    group = xymlWorkflowGroup
    description = "Builds the current checkout with a version inferred from its Git state."
    dependsOn(localBuildTasks)

    doFirst {
        logger.lifecycle("XYML current checkout: ${xymlBranchName ?: "<detached>"}")
        logger.lifecycle("XYML inferred current version: $xymlReleaseVersion")
    }

    doLast {
        if (!nestedBranchBuild.get()) {
            val xymlArtifact = project(":XYML").tasks.named<Jar>("shadowJar").get().archiveFile.get().asFile
            recordRootBuildResult(
                xymlArtifact,
                project(":XYML").version.toString(),
                xymlReleaseChannel,
                xymlBranchName ?: "<detached>"
            )
        }
        promoteRunLibraryCache()
    }
}

if (!nestedBranchBuild.get()) {
    rootBuild.configure {
        outputs.upToDateWhen { false }
        outputs.file(rootBuildResultFile)
    }
}

val runBuildRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName in setOf(
        "run",
        ":run",
        "prepareRunBuild",
        ":prepareRunBuild",
        "runCurrent",
        ":XYML:runCurrent"
    )
}
val runCleanRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':') == "clean"
}
val runLifecycleRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':').let { name ->
        name == "build" || name == "check" || name in setOf("buildMain", "buildBeta", "buildAlpha", "buildDev")
    }
}
val reusableRunLibraries = if (runBuildRequested && !runCleanRequested && !runLifecycleRequested) {
    RunLibraryCache.resolve(runLibraryCacheDirectory.get().asFile.toPath(), runLibraryNames)
} else {
    emptyMap()
}
extra["xymlRunLibraryArtifacts"] = reusableRunLibraries.mapValues { (_, artifact) -> artifact.toFile() }
val temporaryRunLibraries = runBuildRequested && !runLifecycleRequested && reusableRunLibraries.isEmpty()
val temporaryRunLibraryDirectory = layout.buildDirectory.dir("temporary-run-libraries")

if (runBuildRequested) {
    setOf(":XYML", ":XYMLCore", ":XYMLBoot").forEach { projectPath ->
        project(projectPath).tasks.configureEach {
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("XYML run always rebuilds $projectPath") { true }
        }
    }

    project(":XYMLL").tasks.configureEach {
        if (name == "configureXYMLL" || name == "buildNativeXYMLL") {
            onlyIf {
                val reusable = reusableXYMLLNativeOutput()
                if (reusable) {
                    logger.lifecycle("XYML run: reusing the existing XYMLL native output")
                }
                !reusable
            }
        }
    }

    if (temporaryRunLibraries) {
        runLibraryNames.forEach { library ->
            project(":$library").layout.buildDirectory.set(temporaryRunLibraryDirectory.map { it.dir(library) })
            project(":$library").tasks.configureEach {
                outputs.upToDateWhen { false }
                outputs.doNotCacheIf("XYML run uses a temporary $library build") { true }
            }
        }
    }
}

val cleanTemporaryRunLibraries = tasks.register<Delete>("cleanTemporaryRunLibraries") {
    group = "internal"
    description = "Removes library outputs built only for the current run invocation."
    delete(temporaryRunLibraryDirectory)
}

if (temporaryRunLibraries) {
    project(":XYML").tasks.configureEach {
        if (name == "shadowJar") {
            finalizedBy(cleanTemporaryRunLibraries)
        }
    }
}

val prepareRunBuild = tasks.register("prepareRunBuild") {
    group = "internal"
    description = "Rebuilds the current XYML, XYMLCore, and XYMLBoot outputs required by run."
    dependsOn(":XYML:shadowJar")

    doLast {
        val runArtifact = project(":XYML").tasks.named<Jar>("shadowJar").get().archiveFile.get().asFile
        logger.lifecycle("XYML run: rebuilt the current checkout artifact at $runArtifact")
        when {
            reusableRunLibraries.isNotEmpty() -> logger.lifecycle(
                "XYML run: reused the most recent successful build of {}",
                runLibraryNames.joinToString(", ")
            )
            temporaryRunLibraries -> logger.lifecycle(
                "XYML run: used temporary library builds; they were not added to the run-library cache"
            )
            else -> logger.lifecycle("XYML run: used current library project outputs for this combined workflow")
        }
    }
}

tasks.register("run") {
    group = xymlWorkflowGroup
    description = "Rebuilds XYML, XYMLCore, and XYMLBoot, then runs the current checkout artifact."
    dependsOn(prepareRunBuild, ":XYML:runCurrent")
}

defaultTasks("clean", "build")

tasks.register<ParseLanguageSubtagRegistry>("parseLanguageSubtagRegistry") {
    languageSubtagRegistryFile.set(layout.projectDirectory.file("language-subtag-registry"))

    sublanguagesFile.set(layout.projectDirectory.file("XYMLCore/src/main/resources/assets/lang/sublanguages.csv"))
    defaultScriptFile.set(layout.projectDirectory.file("XYMLCore/src/main/resources/assets/lang/default_script.csv"))
}

tasks.register<UpdateDocuments>("updateDocuments") {
    documentsDir.set(layout.projectDirectory.dir("docs"))
}
