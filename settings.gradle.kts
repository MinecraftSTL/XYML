rootProject.name = "XYML"
include(
    "XYML",
    "XYMLCore",
    "XYMLBoot",
    "hello-nbt",
    "lwjgl-unsafe-agent",
    "mesa-loader-windows",
    "XYMLL"
)

project(":hello-nbt").projectDir = file("libraries/hello-nbt")
project(":lwjgl-unsafe-agent").projectDir = file("libraries/lwjgl-unsafe-agent")
project(":mesa-loader-windows").projectDir = file("libraries/mesa-loader-windows")
project(":XYMLL").projectDir = file("libraries/XYMLL")

val minecraftLibraries = listOf("XYMLTransformerDiscoveryService", "XYMLMultiMCBootstrap")
include(minecraftLibraries)

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
