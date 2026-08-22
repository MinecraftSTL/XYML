# XoyzNBT

A powerful library for reading and writing Minecraft NBT data.

It supports:

- Supports reading and writing NBT (Named Binary Tag), in both big-endian (used by Minecraft Java Edition)
  and little-endian (used by Minecraft Bedrock Edition) formats.
- Supports reading and writing Anvil files and region files, including chunk data over 1MiB.
- Supports reading NBT compressed by GZip, Zlib, and LZ4.
- Supports reading and writing SNBT (Stringified Named Binary Tag).
- Supports [NBTPath](https://minecraft.wiki/w/NBT_path) (a query language for NBT data).

To get started, check out the following tutorials:

- Quick Start ([中文](docs/QuickStart_zh.md))
- Advanced Tutorial ([中文](docs/Tutorial_zh.md))

The `javadoc` task builds API documentation for the namespaced XYML fork.

This fork is maintained and consumed by [XYML](https://github.com/MinecraftSTL/XYML).
The API is currently unstable.

## Download

Gradle:

```kotlin
dependencies {
    implementation("space.minecraftstl.xyml:xoyz-nbt:<XYML version>")
}
```

Maven:

```xml

<dependency>
    <groupId>space.minecraftstl.xyml</groupId>
    <artifactId>xoyz-nbt</artifactId>
    <version>&lt;XYML version&gt;</version>
</dependency>
```

## License

This project is licensed under the Apache License, Version 2.0.
