rootProject.name = "XYML"
include(
    "XYML",
    "XYMLCore",
    "XYMLBoot"
)

val minecraftLibraries = listOf("XYMLTransformerDiscoveryService", "XYMLMultiMCBootstrap")
include(minecraftLibraries)

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
