# HelloNBT 快速入门

如果你初次使用 HelloNBT，可以先阅读本节来快速了解 HelloNBT 的基本用法。

## 读取 NBT 文件

HelloNBT 支持读取和写入 NBT 文件（例如 `level.dat`、`playerdata/<UUID>.dat`）、
Anvil 文件和区域文件（`*.mca`/`*.mcr`）。

HelloNBT 提供了 `NBTCodec` 类来读取和写入这些文件。

对于简单的 NBT 文件，可以使用 `readTag` 方法读取一个 NBT Tag：

```java
Tag tag = NBTCodec.of().readTag(Path.of("level.dat"));
```

`readTag` 方法支持自动检测 NBT 数据是否被压缩，并会自动解压使用 `GZip` 或 `LZ4` 算法压缩的数据。

除了从文件中读取，`readTag` 方法还支持从其他来源读取数据，
包括 `InputStream`、`ReadableByteChannel`、`byte[]`、和 `ByteBuffer` 等等：

```java
byte[] bytes = Files.readAllBytes(Path.of("level.dat"));

// 从 byte[] 中读取 NBT 数据
tag = NBTCodec.of().readTag(bytes);

// 从 ByteBuffer 中读取 NBT 数据
tag = NBTCodec.of().readTag(ByteBuffer.wrap(bytes));

// 从 InputStream 中读取 NBT 数据
tag = NBTCodec.of().readTag(new ByteArrayInputStream(bytes));

// 从 ReadableByteChannel 中读取 NBT 数据
tag = NBTCodec.of().readTag(Channels.newChannel(new ByteArrayInputStream(bytes)));
```

在读取 NBT 文件时，可以指定一个 `TagType` 来限制读取的 NBT Tag 类型。

例如 `level.dat` 等文件通常只包含一个 `CompoundTag`，所以可以通过给 `readTag` 方法传入 `TagType.COMPOUND` 来限制读取的 NBT Tag 类型：

```java
CompoundTag tag = NBTCodec.of().readTag(Path.of("level.dat"), TagType.COMPOUND);
```

## 写入 NBT 文件

`NBTCodec` 提供了 `writeTag` 方法来写入 NBT 文件。

目前 `writeTag` 方法只支持写入未经压缩的原始 NBT 数据，用户可以使用 `GZIPOutputStream` 包装输出流来压缩数据：

```java
try (var outputStream = new GZIPOutputStream(Files.newOutputStream(Path.of("level.dat")))) {
    NBTCodec.of().writeTag(outputStream, tag);
}
```

## 读取 Anvil 文件和 Region 文件

Minecraft Java 版的世界区块信息通常存储在后缀为 `.mca` 的 Anvil 文件中。
旧版本 Minecraft 的区块信息则存储在后缀为 `.mcr` 的 Region 文件中。

每个 Anvil 文件和 Region 文件中都存储了一个**区域（Region）**，在 HelloNBT 中使用 `ChunkRegion` 类来表示。

每个区域都包含了 32 x 32 个区块，HelloNBT 使用 `Chunk` 类来表示一个区块。

关于区域和更多信息可以在 Minecraft Wiki 中查看：[区域文件格式](https://zh.minecraft.wiki/w/%E5%8C%BA%E5%9F%9F%E6%96%87%E4%BB%B6%E6%A0%BC%E5%BC%8F)。

在 HelloNBT 中，可以使用 `NBTCodec` 的 `readRegion` 方法来读取 Anvil 文件和 Region 文件：

```java
ChunkRegion region = NBTCodec.of().readRegion(Path.of("region/r.0.0.mca"));
```

## 写入 Anvil 文件和 Region 文件

`NBTCodec` 提供了 `writeRegion` 方法来写入 Anvil 文件和 Region 文件：

```java
NBTCodec.of().writeRegion(Path.of("region/r.0.0.mca"), region);
```

该方法也支持写入到 `OutputStream` 和 `SeekableByteChannel`：

```java
try (var outputStream = Files.newOutputStream(Path.of("region/r.0.0.mca"))) {
    NBTCodec.of().writeRegion(outputStream, region);
}

try (var channel = Files.newByteChannel(Path.of("region/r.0.0.mca"), 
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING)) {
    NBTCodec.of().writeRegion(channel, region);
}
```

由于区域文件格式的限制，无法实现流式写入，所以以 `OutputStream` 为目标时 HelloNBT 会先在内存中构建完整的区域文件，
再写入到输出流中，这可能会导致内存占用较大。
我们更建议使用 `SeekableByteChannel` 作为目标，这样 HelloNBT 可以逐个区块写入通道，避免额外的内存占用。

## 外部区块文件

Anvil 文件中每个区块的数据最大不能超过 1020KiB。

有时一个区块中的数据会超过这个限制，而自 Minecraft 1.15 (19w34a) 开始，
这些过大的区块会被拆分至外部区块文件（`c.<chunkX>.<chunkZ>.mcc`）中存储。

HelloNBT 提供了 `ExternalChunkAccessor` 接口来读取和写入外部区块文件。

默认情况下，`NBTCodec` 中的 `readRegion` 和 `writeRegion` 方法接受 `Path` 参数的重载会检测文件名是否匹配 `r.<regionX>.<regionZ>.mca` 的模式，
如果匹配的话，则会自动使用同目录下的 `c.<chunkX>.<chunkZ>.mcc` 文件作为外部区块文件。
而其他重载则不会自动使用外部区块文件，需要读取/写入外部区块文件时会直接抛出异常。

`readRegion` 和 `writeRegion` 方法也提供了接受 `ExternalChunkAccessor` 参数的重载，
你可以使用这些重载来手动指定外部区块文件的访问方式。

## 完整教程

如果需要更深入地了解 HelloNBT 的用法，可以参阅 [HelloNBT 教程](Tutorial_zh.md)。
