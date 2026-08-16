import space.minecraftstl.xyml.gradle.docs.UpdateDocuments
import space.minecraftstl.xyml.gradle.ci.GitHubActionUtils
import space.minecraftstl.xyml.gradle.ci.JenkinsUtils
import space.minecraftstl.xyml.gradle.l10n.ParseLanguageSubtagRegistry
import space.minecraftstl.xyml.gradle.pack.ReleaseType
import space.minecraftstl.xyml.gradle.pack.ReleaseVersionResolver
import space.minecraftstl.xyml.gradle.utils.PropertiesUtils

plugins {
    id("checkstyle")
    id("org.glavo.gradle-wrapper-neo") version "0.2.0"
}

group = "space.minecraftstl"
version = "3.0"

val projectConfig = PropertiesUtils.load(file("config/project.properties").toPath())
val isOfficialBuild = JenkinsUtils.IS_ON_CI || GitHubActionUtils.IS_ON_OFFICIAL_REPO
val xymlReleaseChannel = System.getenv("RELEASE_CHANNEL")?.takeIf { it.isNotBlank() } ?: "dev"
val xymlReleaseType = ReleaseType.fromName(xymlReleaseChannel)
val xymlStableVersion = System.getenv("STABLE_VERSION")?.takeIf { it.isNotBlank() }
    ?: projectConfig.getProperty("stableVersion")
    ?: "1.0.0"
val xymlExplicitReleaseVersion = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
val xymlBuildNumber = System.getenv("BUILD_NUMBER")?.takeIf { it.isNotBlank() }
val xymlBranchName = sequenceOf("GITHUB_HEAD_REF", "GITHUB_REF_NAME", "CHANGE_BRANCH", "BRANCH_NAME")
    .mapNotNull { variable -> System.getenv(variable)?.takeIf { it.isNotBlank() } }
    .firstOrNull()
    ?: runCatching {
        providers.exec {
            commandLine("git", "branch", "--show-current")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
val xymlReleaseVersion = ReleaseVersionResolver.resolve(
    xymlReleaseType,
    xymlStableVersion,
    xymlExplicitReleaseVersion,
    xymlBuildNumber,
    isOfficialBuild,
    xymlBranchName
)

extra["xymlReleaseVersion"] = xymlReleaseVersion
extra["xymlReleaseChannel"] = xymlReleaseChannel
extra["xymlBranchName"] = xymlBranchName.orEmpty()

subprojects {
    apply {
        plugin("java")
        plugin("idea")
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

defaultTasks("clean", "build")

tasks.register<ParseLanguageSubtagRegistry>("parseLanguageSubtagRegistry") {
    languageSubtagRegistryFile.set(layout.projectDirectory.file("language-subtag-registry"))

    sublanguagesFile.set(layout.projectDirectory.file("XYMLCore/src/main/resources/assets/lang/sublanguages.csv"))
    defaultScriptFile.set(layout.projectDirectory.file("XYMLCore/src/main/resources/assets/lang/default_script.csv"))
}

tasks.register<UpdateDocuments>("updateDocuments") {
    documentsDir.set(layout.projectDirectory.dir("docs"))
}
