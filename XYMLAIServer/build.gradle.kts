plugins {
    application
}

dependencies {
    implementation(project(":XYML"))
    implementation(libs.mcp)
    compileOnly(libs.jetbrains.annotations)
}

application {
    mainClass.set("space.minecraftstl.xyml.ai.Main")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
