plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.compileJava {
    options.compilerArgs.add("--add-exports=jdk.attach/sun.tools.attach=ALL-UNNAMED")
}

dependencies {
    api(libs.kala.compress.zip)
    api(libs.kala.compress.tar)
    api(libs.kala.encoding.detctor)
    api(libs.gson)
    api(libs.tomlj)
    api(libs.xz)
    api(libs.lz4)
    api(libs.constant.pool.scanner)
    api(libs.nanohttpd)
    api(libs.jsoup)
    api(libs.jna)
    api(libs.pci.ids)
    api(project(":xoyz-nbt"))
    api(libs.weburl)
    api(libs.uuid.tools)
    compileOnlyApi(libs.jetbrains.annotations)

    testImplementation(libs.jna.platform)
    testImplementation(libs.jimfs)
}

tasks.processResources {
    listOf(
        "XYMLTransformerDiscoveryService",
        "XYMLMultiMCBootstrap"
    ).map { project(":$it").tasks["jar"] as Jar }.forEach { task ->
        dependsOn(task)

        into("assets/game") {
            from(task.outputs.files)
        }
    }
}
