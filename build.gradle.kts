import space.minecraftstl.xyml.gradle.docs.UpdateDocuments
import space.minecraftstl.xyml.gradle.ci.GitHubActionUtils
import space.minecraftstl.xyml.gradle.ci.JenkinsUtils
import space.minecraftstl.xyml.gradle.l10n.ParseLanguageSubtagRegistry
import space.minecraftstl.xyml.gradle.pack.ReleaseType
import space.minecraftstl.xyml.gradle.pack.ReleaseVersionResolver
import space.minecraftstl.xyml.gradle.pack.GitBranchGradleTask
import space.minecraftstl.xyml.gradle.pack.GitVersionResolver
import space.minecraftstl.xyml.gradle.utils.PropertiesUtils

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
    ReleaseVersionResolver.resolve(
        xymlReleaseType,
        xymlStableVersion,
        xymlExplicitReleaseVersion,
        xymlBuildNumber,
        isOfficialBuild
    )
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

val xymlWorkflowGroup = "XYML workflows"
val nestedBranchBuild = providers.gradleProperty("xyml.branchBuild.nested")
    .map { it.toBooleanStrict() }
    .orElse(false)
val fetchReleaseBranches = providers.gradleProperty("xyml.branchBuild.fetch")
    .map { it.toBooleanStrict() }
    .orElse(true)
val configuredGitProxy = providers.gradleProperty("xyml.branchBuild.gitProxy")

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

tasks.register("build") {
    group = xymlWorkflowGroup
    description = "Builds the latest matching release branch, or the current checkout as a Git-derived feature build."
    if (nestedBranchBuild.get() || xymlBranchReleaseType == null) {
        dependsOn(localBuildTasks)
        doFirst {
            logger.lifecycle("XYML feature checkout: ${xymlBranchName ?: "<detached>"}")
            logger.lifecycle("XYML inferred feature version: $xymlReleaseVersion")
        }
    } else {
        dependsOn(releaseBranchBuilds.getValue(xymlBranchReleaseType))
    }
}

if (nestedBranchBuild.get() || xymlBranchReleaseType == null) {
    tasks.register("run") {
        group = xymlWorkflowGroup
        description = "Runs the current feature or detached checkout with its Git-derived version."
        dependsOn(":XYML:run")
    }
} else {
    tasks.register<GitBranchGradleTask>("run") {
        group = xymlWorkflowGroup
        description = "Runs the latest origin/${xymlBranchName} commit with its inferred release version."
        branchName.set(xymlBranchName)
        releaseType.set(xymlBranchReleaseType)
        gradleArguments.set(listOf(
            ":XYML:run",
            "-Pxyml.branchBuild.nested=true",
            "--no-daemon",
            "--stacktrace"
        ))
        fetchRemote.set(fetchReleaseBranches)
        gitProxy.set(configuredGitProxy)
        repositoryDirectory.set(layout.projectDirectory)
    }
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
