# HelloNBT

[![codecov](https://codecov.io/gh/HMCL-dev/HelloNBT/graph/badge.svg?token=M1XJHATTPN)](https://codecov.io/gh/HMCL-dev/HelloNBT)
[![](https://img.shields.io/maven-central/v/org.glavo/HelloNBT?label=Maven%20Central)](https://search.maven.org/artifact/org.glavo/HelloNBT)
[![javadoc](https://javadoc.io/badge2/org.glavo/HelloNBT/javadoc.svg)](https://javadoc.io/doc/org.glavo/HelloNBT)

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

We also provide detailed documentation for all APIs, which can be found here:
[HelloNBT Javadoc](https://javadoc.io/doc/org.glavo/HelloNBT).

This library is used for the [Hello! Minecraft Launcher](https://github.com/HMCL-Dev-HMCL).
The API is currently unstable.

## Download

Gradle:

```kotlin
dependencies {
    implementation("space.minecraftstl.xyml:hello-nbt:<XYML version>")
}
```

Maven:

```xml

<dependency>
    <groupId>space.minecraftstl.xyml</groupId>
    <artifactId>hello-nbt</artifactId>
    <version>&lt;XYML version&gt;</version>
</dependency>
```

## License

This project is licensed under the Apache License, Version 2.0.
