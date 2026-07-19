plugins {
    id("java-library")
}

dependencies {
    compileOnlyApi(libs.jetbrains.annotations)
}

tasks.compileJava {
    options.release.set(8)
}

tasks.compileTestJava {
    options.release.set(17)
}
