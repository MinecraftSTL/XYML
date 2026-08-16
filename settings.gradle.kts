rootProject.name = "XYML"
include(
    "XYML",
    "XYMLCore",
    "XYMLBoot",
    "hello-nbt",
    "lwjgl-unsafe-agent"
)

project(":hello-nbt").projectDir = file("libraries/hello-nbt")
project(":lwjgl-unsafe-agent").projectDir = file("libraries/lwjgl-unsafe-agent")

val minecraftLibraries = listOf("XYMLTransformerDiscoveryService", "XYMLMultiMCBootstrap")
include(minecraftLibraries)

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
