# HelloNBT 教程

本文将详细介绍 HelloNBT 的用法。

如果你初次使用 HelloNBT，可以先阅读 [快速入门](/docs/QuickStart_zh.md) 来快速了解 HelloNBT 的基本用法。

## 基础

HelloNBT 提供了对 NBT 树的基本抽象。

所有 NBT 元素都实现了 `NBTElement` 接口，该接口有以下实现：

- `NBTElement`: 代表任意 NBT元素。
    - `ChunkRegion`: 代表一个区域（Region）。一个区域中包含 32 x 32 个区块。
    - `Chunk`: 代表一个区块。`Chunk` 对象中中记录了对区块的最后更新时间，以及一个可选的用于存储 NBT 数据的 `CompoundTag`。
    - `Tag`: 代表一个 NBT 标签。
        - `ParentTag`: 代表可以包含其他 NBT 标签的 NBT 标签。
            - `CompoundTag`: 代表一个复合 NBT 标签。其中可以包含多个具有不同名称的 NBT 标签。
            - `ListTag`: 代表一个列表 NBT 标签。所有 NBT 标签不包含名称，且类型相同。
            - `ArrayTag`: 代表一个数组 NBT 标签。所有 NBT 标签不包含名称，且类型相同。
                - `ByteArrayTag`: 代表包含一系列 `ByteTag` 的数组 NBT 标签。
                - `IntArrayTag`: 代表包含一系列 `IntTag` 的数组 NBT 标签。Minecraft 也会用它来存储 UUID。
                - `LongArrayTag`: 代表包含一系列 `LongTag` 的数组 NBT 标签。
        - `ValueTag`: 代表包含一个值的 NBT 标签。
            - `ByteTag`: 包含一个单字节整数的 NBT 标签。Minecraft 也会用它来存储布尔值。
            - `ShortTag`: 包含一个双字节整数的 NBT 标签。
            - `IntTag`: 包含一个四字节整数的 NBT 标签。
            - `LongTag`: 包含一个八字节整数的 NBT 标签。
            - `FloatTag`: 包含一个单精度浮点数的 NBT 标签。
            - `DoubleTag`: 包含一个双精度浮点数的 NBT 标签。
            - `StringTag`: 包含一个字符串的 NBT 标签。

其中 `ChunkRegion`、`Chunk`、`CompoundTag`、`ListTag`、`ByteArrayTag`、`IntArrayTag` 和 `LongArrayTag` 
可以包含其他 `NBTElement` 作为子元素，这些可以作为父元素的 `NBTElement` 都实现了 `NBTParent` 接口。

一个没有父元素的 `NBTElement` 称为根元素（Root Element）。根元素及其所有子元素共同构成了一个 NBT 树。

每个 `NBTElement` 只能处于一个 NBT 树中，且在树中是唯一的。

尝试将一个 `NBTElement` 添加到另一个 `NBTParent` 中时，它会先被从当前父元素中移除，再添加到新的父元素中。

所有 `NBTElement` 都支持 `clone` 方法进行复制。
`clone` 方法会递归复制其所有子元素，返回的副本将与原元素具有相同的内容，但是与原元素完全独立，且是没有父级的根元素。
用户可以将 `clone` 出的元素添加至其他 `NBTParent` 中，从而避免影响原 NBT 树。

## 构造 NBT 标签

大部分 `Tag` 都可以简单的通过构造方法创建：

```java
// 创建一个 IntTag
var _ = new IntTag();

// 创建一个值为 123 的 IntTag
var _ = new IntTag(123);

// 创建一个空的 CompoundTag
var _ = new CompoundTag();
```

构造 `ListTag` 时需要传入其元素类型：

```java
ListTag<IntTag> listTag = new ListTag<>(TagType.INT);
```

对于 `ValueTag`，除了可以在构造时传入值，也可以通过 `set` 方法修改其值：

```java
var intTag = new IntTag().set(123);
```

对于 `CompoundTag`，可以通过 `add` 系列方法添加子标签：

```java
var compoundTag = new CompoundTag()
        // 添加一个 IntTag 子标签，其名称为 "int"，值为 123
        .addInt("int", 123)
        // 添加一个 StringTag 子标签，其名称为 "str"，值为 "HelloNBT"
        .addString("str", "HelloNBT")
        // addTag 方法可以添加任意子标签
        .addTag("compound", new CompoundTag()
                .addInt("nestedInt", 456));
```

`add` 系列的方法支持链式调用，可以方便地构建复杂的 NBT 树。
当添加的新元素与已存在的元素具有相同的名称时，新元素会覆盖旧元素。

此外，`CompoundTag` 还提供了 `set` 系列的方法，用于修改已存在的子标签的值。

`set` 系列的方法与 `add` 系列的方法类似，但不会覆盖已存在的元素，而是修改其值：

```java
// 先获取 "int" 子标签
var intSubTag = (IntTag) compoundTag.get("int");

// 修改 "int" 子标签的值
compoundTag.setInt("int", 233);

// 子标签的值被修改，而不是被新的子标签替换
assert intSubTag.get() == 233;

// 如果使用 addInt 方法，则会使用新的子标签替换 intSubTag，intSubTag 的值不会随之变化
compoundTag.addInt("int", 456);
assert intSubTag.get() == 233;

```

在不存在该子标签时，`set` 系列的方法会创建一个新的子标签并添加到 `CompoundTag` 中：

```java
assert compoundTag.getTag("anotherInt") == null;

compoundTag.setInt("anotherInt", 456);

assert ((IntTag) compoundTag.getTag("anotherInt")).get() == 456;
```

如果已存在的子标签类型与要设置的值类型不匹配，`set` 系列的方法会抛出 `IllegalStateException`：

```java
try {
    compoundTag.setInt("string", 233);
} catch (IllegalStateException e) {
    // Expected IllegalStateException
}
```

## NBTPath

HelloNBT 提供了对 NBTPath 的支持。

NBTPath 是一种查询 NBT 数据的语言，相关文档可以在 Minecraft Wiki
中查看：[NBTPath](https://zh.minecraft.wiki/w/NBT%E8%B7%AF%E5%BE%84)。

HelloNBT 中可以使用 `NBTPath.of(String)` 来解析 NBTPath 字符串：

```java
NBTPath<?> path = NBTPath.of("a.b");
```

NBTPath 可以附加一个 `TagType`，用于指定匹配的 NBT 元素类型：

```java
NBTPath<IntTag> path = NBTPath.of("a.b").withTagType(TagType.INT);
```

在获取到一个 NBTPath 后，可以使用 `NBTParent#getAllTags(NBTPath)` 方法来获取所有匹配的 NBT Tag，
或者用 `NBTParent#getFirstTag(NBTPath)` 方法来获取第一个匹配的 NBT 元素：

```java
// 获取所有匹配的 NBT Tag
List<IntTag> _ = compoundTag.getAllTags(path).toList();

// 获取第一个匹配的 NBT Tag，如果不存在则抛出 NoSuchElementException
IntTag _ = compoundTag.getFirstTag(path);

// 获取第一个匹配的 NBT Tag 的值
int _       = compoundTag.getFirstInt(path);             // 如果不存在则抛出 NoSuchElementException
Integer _   = compoundTag.getFirstIntOrNull(path);       // 如果不存在则返回 null
int _       = compoundTag.getFirstIntOrDefault(path, 0); // 如果不存在则返回默认值
```

## 读写 NBT 数据

HelloNBT 支持读取和写入 NBT 文件、Anvil 文件（`.mca`）和区域文件（`.mcr`）。

### `NBTCodec`

`NBTCodec` 类提供了读取和写入 NBT 文件、Anvil 文件和区域文件的方法。

可以通过 `NBTCodec.of()` 工厂方法来获取一个 `NBTCodec` 实例：

```java
NBTCodec _ = NBTCodec.of();

// 如果需要读写基岩版的 NBT 数据，可以传入 MinecraftEdition.BEDROCK 参数
NBTCodec _ = NBTCodec.of(MinecraftEdition.BEDROCK);
```

`NBTCodec` 是不可变的，可以在多个线程中安全地共享。

在获取到 `NBTCodec` 实例后，也可以通过 `with` 系列方法来产生新的 `NBTCodec` 实例：

```java
NBTCodec codec = NBTCodec.of()
        // 设置 Minecraft Edition
        .withEdition(MinecraftEdition.BEDROCK)
        // 设置外部区块文件访问器工厂
        .withExternalChunkAccessorFactory(ExternalChunkAccessor.emptyFactory());
```

`with` 系列方法不会修改原 `NBTCodec` 实例，每次调用都会返回一个新的 `NBTCodec` 实例。

### 读取 NBT 文件

`NBTCodec` 提供了 `readTag` 方法来读取一个 NBT Tag：

```java
Tag tag = NBTCodec.of().readTag(Path.of("level.dat"));
```

`readTag` 方法支持自动检测 NBT 数据是否被压缩，并会自动解压使用 `GZip` 或 `LZ4` 算法压缩的数据。

> [!NOTE]
> 
> HelloNBT 需要运行时存在 [lz4-java](https://github.com/lz4/lz4-java) 库才能读取使用 LZ4 压缩的 NBT 数据。
> 
> lz4-java 官方已经放弃维护，推荐使用以下社区维护的分支：
> 
> - [Glavo/lz4-java](https://github.com/Glavo/lz4-java) (轻量级分支，提供经过优化的纯 Java 安全实现，体积较小（约 80KiB）)
> - [yawkat/lz4-java](https://github.com/yawkat/lz4-java) (原版分支，提供基于 `Unsafe` 和 JNI 的高性能实现，但体积较大（约 770KiB）)

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

### 写入 NBT 文件

`NBTCodec` 提供了 `writeTag` 方法来写入 NBT 文件。

目前 `writeTag` 方法只支持写入未经压缩的原始 NBT 数据，用户可以使用 `GZIPOutputStream` 包装输出流来压缩数据：

```java
try (var outputStream = new GZIPOutputStream(Files.newOutputStream(Path.of("level.dat")))) {
    NBTCodec.of().writeTag(outputStream, tag);
}
```

### 读取 Anvil 文件和 Region 文件

Minecraft Java 版的世界区块信息通常存储在后缀为 `.mca` 的 Anvil 文件中。
旧版本 Minecraft 的区块信息则存储在后缀为 `.mcr` 的 Region 文件中。

每个 Anvil 文件和 Region 文件中都存储了一个**区域（Region）**，在 HelloNBT 中使用 `ChunkRegion` 类来表示。

每个区域都包含了 32 x 32 个区块，HelloNBT 使用 `Chunk` 类来表示一个区块。

关于区域和更多信息可以在 Minecraft Wiki 中查看：[区域文件格式](https://zh.minecraft.wiki/w/%E5%8C%BA%E5%9F%9F%E6%96%87%E4%BB%B6%E6%A0%BC%E5%BC%8F)。

在 HelloNBT 中，可以使用 `NBTCodec` 的 `readRegion` 方法来读取 Anvil 文件和 Region 文件：

```java
ChunkRegion region = NBTCodec.of().readRegion(Path.of("region/r.0.0.mca"));
```

### 写入 Anvil 文件和 Region 文件

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

### 外部区块文件

Anvil 文件中每个区块的数据最大不能超过 1020KiB。

有时一个区块中的数据会超过这个限制，而自 Minecraft 1.15 (19w34a) 开始，
这些过大的区块会被拆分至外部区块文件（`c.<chunkX>.<chunkZ>.mcc`）中存储。

HelloNBT 提供了 `ExternalChunkAccessor` 接口来读取和写入外部区块文件。

默认情况下，`NBTCodec` 中的 `readRegion` 和 `writeRegion` 方法接受 `Path` 参数的重载会检测文件名是否匹配 `r.<regionX>.<regionZ>.mca` 的模式，
如果匹配的话，则会自动使用同目录下的 `c.<chunkX>.<chunkZ>.mcc` 文件作为外部区块文件。
而其他重载则不会自动使用外部区块文件，需要读取/写入外部区块文件时会直接抛出异常。

`readRegion` 和 `writeRegion` 方法也提供了接受 `ExternalChunkAccessor` 参数的重载，
你可以使用这些重载来手动指定外部区块文件的访问方式。

## 读写 SNBT 数据

HelloNBT 还支持读取和写入 SNBT（Stringified Named Binary Tag）数据。

### `SNBTCodec`

`SNBTCodec` 类提供了读取和写入 SNBT 数据的方法。

可以通过 `SNBTCodec.of()` 工厂方法来获取一个 `SNBTCodec` 实例：

```java
SNBTCodec _ = SNBTCodec.of();
```

`SNBTCodec` 是不可变的，可以在多个线程中安全地共享。

目前所有 `SNBTCodec` 实例在读取 SNBT 时行为是一致的，但可以通过通过 `with` 系列方法来调整输出的 SNBT 格式：

```java
SNBTCodec codec = SNBTCodec.of()
        // 设置换行策略
        .withLineBreakStrategy(LineBreakStrategy.defaultStrategy())
        // 设置缩进
        .withIndentation(4)
        // 设置括号、分号、逗号、括号周围的空格
        .withSurroundingSpaces(SurroundingSpaces.PRETTY)
        // 设置字符串转义策略
        .withEscapeStrategy(EscapeStrategy.defaultStrategy())
        // 设置名称字符串和值字符串的引号策略
        .withNameQuoteStrategy(QuoteStrategy.defaultNameStrategy())
        .withValueQuoteStrategy(QuoteStrategy.defaultValueStrategy());
```

`with` 系列方法不会修改原 `SNBTCodec` 实例，每次调用都会返回一个新的 `SNBTCodec` 实例。


### 读取 SNBT 数据

`SNBTCodec` 提供了 `readTag` 方法来读取 SNBT 数据：

```java
Tag _ = SNBTCodec.of().readTag("HelloNBT");

try (var reader = Files.newBufferedReader(Path.of("test.snbt"))) {
    Tag _ = SNBTCodec.of().readTag(reader);
}
```

### 写入 SNBT 数据

`SNBTCodec` 提供了 `writeTag` 方法来写入 SNBT 数据：

```java
var builder = new StringBuilder();
SNBTCodec.of().writeTag(builder, tag);

try (var writer = Files.newBufferedWriter(Path.of("test.snbt"))) {
    writer.write(builder.toString());
}
```

`SNBTCodec` 还提供了一个 `toString(Tag)` 便捷方法，可以直接将 NBT 标签转换为 SNBT 字符串：

```java
String snbt = SNBTCodec.of().toString(tag);
```

### 异构 List 标签

与 NBT 的二进制表示不同，SNBT 支持异构 List 标签。

在 SNBT 中，如果 List 标签包含不同类型的元素，那么所有非 Compound 标签都会被转换为一个 Compound 标签，
这个 Compound 标签中包含一个名称为空的子标签，子标签的值为原始的非 Compound 标签。

HelloNBT 的 `ListTag` 类支持模拟这种行为。

为了方便的模拟异构 List 标签，在构造 `ListTag` 时不应该传入元素类型参数：

```java
ListTag<Tag> listTag = new ListTag<>();
```

对于这样的 List 标签，应当使用 `addAnyTag` 方法添加子标签：

```java
listTag.addAnyTag(new IntTag(123))
listTag.addAnyTag(new StringTag("HelloNBT"));

assert listTag.equals(new ListTag<>(TagType.COMPOUND)
        .addTag(new CompoundTag().addInt("", 123))
        .addTag(new CompoundTag().addString("", "HelloNBT")));
```

## NBT 验证

TODO: 实现 NBTSchema
