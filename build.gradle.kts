import space.minecraftstl.xyml.gradle.docs.UpdateDocuments
import space.minecraftstl.xyml.gradle.ci.GitHubActionUtils
import space.minecraftstl.xyml.gradle.ci.JenkinsUtils
import space.minecraftstl.xyml.gradle.l10n.ParseLanguageSubtagRegistry
import space.minecraftstl.xyml.gradle.pack.ReleaseType
import space.minecraftstl.xyml.gradle.pack.ReleaseVersionResolver
import space.minecraftstl.xyml.gradle.pack.GitBranchGradleTask
import space.minecraftstl.xyml.gradle.pack.GitVersionResolver
import space.minecraftstl.xyml.gradle.utils.PropertiesUtils
import java.nio.file.Files
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
val fetchReleaseBranches = providers.gradleProperty("xyml.branchBuild.fetch")
    .map { it.toBooleanStrict() }
    .orElse(true)
val configuredGitProxy = providers.gradleProperty("xyml.branchBuild.gitProxy")

val rootBuildResultFile = layout.buildDirectory.file("root-build-result.properties")

fun findReusableRootBuildArtifact(): File? {
    val marker = rootBuildResultFile.get().asFile
    if (!marker.isFile) {
        return null
    }

    return runCatching {
        val properties = PropertiesUtils.load(marker.toPath())
        if (properties.getProperty("task") != ":build") {
            return@runCatching null
        }

        val relativeArtifact = properties.getProperty("artifact")?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val rootPath = rootDir.toPath().toAbsolutePath().normalize()
        val artifactPath = rootPath.resolve(relativeArtifact).normalize()
        if (!artifactPath.startsWith(rootPath) || !Files.isRegularFile(artifactPath)) {
            null
        } else {
            artifactPath.toFile()
        }
    }.getOrNull()
}

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

fun releaseBranchArtifact(branchName: String): Pair<File, String> {
    val artifactDirectory = layout.buildDirectory.dir("channel-builds/$branchName").get().asFile
    val buildInfoFile = artifactDirectory.resolve("build-info.properties")
    check(buildInfoFile.isFile) {
        "Root :build completed without channel build metadata: $buildInfoFile"
    }

    val buildInfo = PropertiesUtils.load(buildInfoFile.toPath())
    check(buildInfo.getProperty("branch") == branchName) {
        "Channel build metadata does not match branch $branchName: $buildInfoFile"
    }
    val version = buildInfo.getProperty("version")?.takeIf { it.isNotBlank() }
        ?: error("Channel build metadata does not contain a version: $buildInfoFile")
    return artifactDirectory.resolve("XYML-$version.jar") to version
}

fun registerReleaseBranchBuild(taskName: String, branchName: String, releaseType: ReleaseType) =
    tasks.register<GitBranchGradleTask>(taskName) {
        group = xymlWorkflowGroup
        description = "Builds the latest origin/$branchName commit with an inferred ${releaseType.getName()} version."
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
        fetchRemote.set(fetchReleaseBranches)
        gitProxy.set(configuredGitProxy)
        repositoryDirectory.set(layout.projectDirectory)
        artifactDirectory.set(layout.buildDirectory.dir("channel-builds/$branchName"))
    }

val buildMain = registerReleaseBranchBuild("buildMain", "main", ReleaseType.STABLE)
val buildBeta = registerReleaseBranchBuild("buildBeta", "beta", ReleaseType.BETA)
val buildAlpha = registerReleaseBranchBuild("buildAlpha", "alpha", ReleaseType.ALPHA)
val buildDev = registerReleaseBranchBuild("buildDev", "dev", ReleaseType.DEV)
val releaseBranchBuilds = mapOf(
    ReleaseType.STABLE to buildMain,
    ReleaseType.BETA to buildBeta,
    ReleaseType.ALPHA to buildAlpha,
    ReleaseType.DEV to buildDev
)
val localBuildTasks = subprojects.map { "${it.path}:build" }
val localCleanTasks = subprojects.map { "${it.path}:clean" }

tasks.register<Delete>("clean") {
    group = xymlWorkflowGroup
    description = "Cleans build output for the current checkout without fetching or switching branches."
    dependsOn(localCleanTasks)
    delete(layout.buildDirectory, layout.projectDirectory.dir("buildSrc/build"))
}

val rootBuild = tasks.register("build") {
    group = xymlWorkflowGroup
    description = "Builds the latest matching release branch, or the current checkout as a Git-derived feature build."
}

if (!nestedBranchBuild.get()) {
    rootBuild.configure {
        outputs.upToDateWhen { false }
        outputs.file(rootBuildResultFile)
    }
}

if (nestedBranchBuild.get() || xymlBranchReleaseType == null) {
    rootBuild.configure {
        dependsOn(localBuildTasks)
        doFirst {
            logger.lifecycle("XYML feature checkout: ${xymlBranchName ?: "<detached>"}")
            logger.lifecycle("XYML inferred feature version: $xymlReleaseVersion")
        }

        if (!nestedBranchBuild.get()) {
            doLast {
                val xymlArtifact = project(":XYML").tasks.named<Jar>("shadowJar").get().archiveFile.get().asFile
                recordRootBuildResult(
                    xymlArtifact,
                    project(":XYML").version.toString(),
                    xymlReleaseChannel,
                    xymlBranchName ?: "<detached>"
                )
            }
        }
    }
} else {
    rootBuild.configure {
        dependsOn(releaseBranchBuilds.getValue(xymlBranchReleaseType))
        doLast {
            val branchName = xymlBranchName ?: error("Release branch build is missing its branch name")
            val (artifact, version) = releaseBranchArtifact(branchName)
            recordRootBuildResult(artifact, version, xymlReleaseChannel, branchName)
        }
    }
}

val cleanRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':') == "clean"
}
val reusableRunArtifact = if (cleanRequested) null else findReusableRootBuildArtifact()

if (reusableRunArtifact == null) {
    project(":XYML").tasks.configureEach {
        if (name == "shadowJar") {
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Temporary run artifacts are not reusable root build results") { true }
        }
    }
}

val prepareRunBuild = tasks.register("prepareRunBuild") {
    group = "internal"
    description = "Temporarily builds the current checkout for run when no root :build result is available."
    if (reusableRunArtifact == null) {
        dependsOn(":XYML:shadowJar")
    }

    doLast {
        if (reusableRunArtifact == null) {
            logger.lifecycle("XYML run: prepared an unrecorded temporary artifact from incremental project outputs")
        } else {
            logger.lifecycle("XYML run: reusing the last root :build result at $reusableRunArtifact")
        }
    }
}

tasks.register("run") {
    group = xymlWorkflowGroup
    description = "Runs XYML from the current checkout, reusing the last root :build result when available."
    dependsOn(prepareRunBuild, ":XYML:runFromBuildResult")
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
