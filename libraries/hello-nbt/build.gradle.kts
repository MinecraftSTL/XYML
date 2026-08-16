import com.sun.management.OperatingSystemMXBean
import org.glavo.gradle.UncompressResources
import java.lang.management.ManagementFactory
import kotlin.math.max

plugins {
    id("java-library")
    id("jacoco")
    id("maven-publish")
    id("signing")
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("org.glavo.load-maven-publish-properties") version "0.1.0"
}

group = "org.glavo"
version = "0.4.0" // + "-SNAPSHOT"
description = "A modern Java library for reading and writing Minecraft NBT files."

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    compileOnlyApi(libs.jetbrains.annotations)
    compileOnly(libs.lz4)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.opennbt)
    testImplementation(libs.lz4)
    testImplementation(libs.xz)
    testImplementation(libs.commons.io)
    testImplementation(libs.jimfs)
}

java {
    withSourcesJar()
    withJavadocJar()
}

val mainClassName = "org.glavo.nbt.internal.cli.Main"

tasks.jar {
    manifest.attributes(
        "Main-Class" to mainClassName,
        "HelloNBT-Version" to project.version.toString(),
    )
}

tasks.withType(JavaCompile::class) {
    options.release.set(17)
    options.javaModuleMainClass.set(mainClassName)
    options.javaModuleVersion.set(project.version.toString())
}

val run by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar)
    mainModule = "org.glavo.nbt"
    classpath(tasks.jar.map { it.archiveFile })
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).also {
        it.jFlags!!.addAll(listOf("-Duser.language=en", "-Duser.country=", "-Duser.variant="))

        it.encoding("UTF-8")
        it.addStringOption("link", "https://docs.oracle.com/en/java/javase/25/docs/api/")
        it.addBooleanOption("html5", true)
        it.addStringOption("Xdoclint:none", "-quiet")

        it.tags!!.addAll(
            listOf(
                "apiNote:a:API Note:",
                "implNote:a:Implementation Note:",
                "implSpec:a:Implementation Specification:",
            )
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true

    // Use more parallelism on large machines
    if ((ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).totalMemorySize >= 14L * 1024L * 1024L * 1024L) {
        maxParallelForks = max(1, Runtime.getRuntime().availableProcessors() / 2)
    }
}

val uncompressResources by tasks.registering(UncompressResources::class) {
    inputDir.set(project.layout.projectDirectory.dir("src/test/resources-compressed"))
    outputDir.set(project.layout.buildDirectory.dir("generated/test/resources-uncompressed"))
}

tasks.processTestResources {
    dependsOn(uncompressResources)
    from(uncompressResources.map { it.outputDir })
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing.publications.create<MavenPublication>("maven") {
    groupId = project.group.toString()
    version = project.version.toString()
    artifactId = project.name

    from(components["java"])

    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/HMCL-dev/HelloNBT")

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("Glavo")
                name.set("Glavo")
                email.set("zjx001202@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/HMCL-dev/HelloNBT")
        }
    }
}

if (rootProject.ext.has("signing.key")) {
    signing {
        useInMemoryPgpKeys(
            rootProject.ext["signing.keyId"].toString(),
            rootProject.ext["signing.key"].toString(),
            rootProject.ext["signing.password"].toString(),
        )
        sign(publishing.publications["maven"])
    }
}

// ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username.set(rootProject.ext["sonatypeUsername"].toString())
            password.set(rootProject.ext["sonatypePassword"].toString())
        }
    }
}
