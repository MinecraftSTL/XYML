rootProject.name = "XYML"
include(
    "XYML",
    "XYMLCore",
    "XYMLBoot",
    "hello-nbt"
)

project(":hello-nbt").projectDir = file("libraries/hello-nbt")

val minecraftLibraries = listOf("XYMLTransformerDiscoveryService", "XYMLMultiMCBootstrap")
include(minecraftLibraries)

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
