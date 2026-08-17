plugins {
    java
}

repositories {
    System.getenv("MAVEN_CENTRAL_REPO").let { repo ->
        if (repo.isNullOrBlank())
            mavenCentral()
        else
            maven(url = repo)
    }
}

dependencies {
    compileOnly(gradleApi())
    compileOnly(libs.jetbrains.annotations)

    implementation(libs.gson)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.kala.compress.tar)
    implementation(libs.kala.compress.ar)
    implementation(libs.weburl)
    implementation(libs.xz)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processResources {
    into("space/minecraftstl/xyml/gradle/l10n") {
        from(projectDir.resolve("../XYMLCore/src/main/resources/assets/lang/"))
    }
}
