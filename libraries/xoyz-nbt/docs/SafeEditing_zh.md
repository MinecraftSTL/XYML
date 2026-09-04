# XoyzNBT 安全编辑指南

XoyzNBT 将“修改内存中的 NBT 树”和“把修改安全发布到文件”分成两层：

- `NBTEditor<E>` 负责结构约束、原子编辑、undo/redo、revision 和保存点。
- `NBTFile<E>` 负责严格读取、编码保持、外部冲突检测、校验回读和文件发布。
- `NBTRegionFile` 是更低层的 Region copy-on-write 存储接口，适合需要逐 chunk 控制的调用方。

这些接口是通用库 API，不包含扩展名选择、线程调度或界面逻辑。调用方应在自己的 I/O 线程中打开和保存文件。

## 编辑 standalone NBT 文件

`NBTFile.openTag` 会完整解析输入并拒绝截断数据、尾随数据和损坏的压缩校验信息。保存时会保留打开时检测到的 RAW、GZIP、ZLIB 或 LZ4 外层编码。

```java
Path source = Path.of("level.dat");

try (NBTFile<CompoundTag> file = NBTFile.openTag(source, TagType.COMPOUND)) {
    NBTEditor<CompoundTag> editor = file.getEditor();
    NBTAddress gameTypeAddress = NBTAddress.root()
            .appendName("Data")
            .appendName("GameType");
    NBTNode gameType = editor.resolve(gameTypeAddress);

    editor.setScalar(gameType, "1");
    file.save(NBTSaveOptions.withBackup(source.resolveSibling("level.dat_old")));
}
```

`NBTSaveOptions.withBackup` 接受任意不同于源文件的备份路径。备份是单份滚动副本，每次成功保存前保存源文件的完整编码字节。

Standalone 保存流程为：

1. 对保存快照做完整结构校验并序列化。
2. 在目标目录创建暂存文件并 `force`。
3. 严格回读暂存文件并比较语义内容。
4. 再次核对源文件指纹。
5. 可选地原子发布滚动备份。
6. 使用原子替换发布源文件。

文件系统不支持原子替换时，保存会失败，不会退化为覆盖原文件。

## 节点句柄与地址

`NBTNode` 只包含只读元数据，不暴露编辑器工作树中的可变对象。每个节点句柄绑定创建它的编辑会话和 revision；任何成功修改、undo 或 redo 都会推进 revision，旧句柄随后会以 `NBTEditException.Reason.STALE_NODE` 被拒绝。

需要继续编辑时，应通过稳定地址重新解析节点：

```java
NBTAddress address = NBTAddress.root().appendName("Data").appendName("LevelName");
NBTNode node = editor.resolve(address);
editor.setScalar(node, "New world");

// node 已失效；在新 revision 中重新解析。
NBTNode currentNode = editor.resolve(address);
```

地址段与容器语义一一对应：

- Compound 子项使用 `appendName(name)`。
- List 和 primitive array 元素使用 `appendIndex(index)`。
- Region 固定槽使用 `appendChunk(localIndex)`，范围是 0 到 1023。
- Chunk 根标签使用 `appendChunkRoot()`。

`editor.snapshot()`、`editor.snapshot(address)` 和 `editor.detachedSnapshot(node)` 都返回深拷贝。修改这些返回值不会绕过编辑器，也不会改变工作树。

## 事务操作与约束

常用操作包括：

- `insertTag`：向 Compound、List、primitive array 或空 Chunk 插入 detached Tag。
- `replace` / `replaceContent`：显式替换节点或仅替换 Tag 内容。
- `rename`：重命名 Compound 的直接子项。
- `remove` / `delete`：删除非根节点。
- `move`：在兼容容器之间移动节点或在同一容器内排序。
- `setScalar` / `setArrayElement`：按原类型严格解析并更新标量。
- `setListElementType`：仅为符合约束的空 List 设置元素类型。
- `setChunkTimestamp`：更新 Region chunk 的时间戳。

编辑器会先完成全部预检，再一次性提交修改。名称冲突、List 异构、非法索引、循环引用、双重归属、根节点禁用操作或数值越界都不会留下部分修改。输入根、插入对象和替换对象都会被深拷贝。

`NBTEditException.reason()` 提供稳定的机器可读原因，例如 `STALE_NODE`、`DUPLICATE_NAME`、`TYPE_MISMATCH`、`CYCLE`、`ROOT_OPERATION`、`INVALID_FORMAT`、`NO_UNDO` 和 `NO_REDO`。

## Undo、redo 与保存点

默认编辑器保留最近 100 条局部历史，也可以用 `NBTEditor.of(root, historyLimit)` 指定上限：

```java
NBTEditor<CompoundTag> editor = NBTEditor.of(root, 200);
editor.rename(editor.resolve(NBTAddress.root().appendName("OldName")), "NewName");
editor.undo();
editor.redo();
```

异步保存应先捕获 `NBTSavepoint`，不要在 I/O 线程中直接读取正在变化的工作树：

```java
NBTSavepoint<CompoundTag> savepoint = editor.saveSnapshot();
CompoundTag detached = savepoint.root();

// 在调用方选择的 I/O 线程中安全写出 detached。
codec.writeTag(target, detached);

// 只有保存期间没有晚到编辑时才会变为 clean。
boolean becameClean = editor.markSaved(savepoint);
```

`NBTFile.save` 已封装这一保存点协议。保存期间出现的新编辑会继续保持 dirty，不会被较旧的 I/O 完成事件错误清除。

## 严格编解码与结构校验

所有 writer 都会在写出首字节前运行 `NBTStructureValidator`。校验范围包括循环、对象重复归属、parent/index、Compound 名称映射、List 元素类型、primitive array 状态以及当前 Edition 的字符串编码长度。

`SNBTCodec` 仍只负责通用 SNBT 解析和格式化。高级编辑流程应完整解析 SNBT，然后将 detached Tag 交给 `NBTEditor.insertTag`、`replace` 或 `replaceContent`；不要直接修改编辑器内部对象。

以 `Path` 为目标的 `NBTCodec.writeRegion` 会委托安全的 Region 路径实现。`OutputStream` 和 `SeekableByteChannel` 重载会保证结构错误发生在首字节之前，但无法提供路径级冲突检测和原子发布；编辑已有文件时优先使用 `NBTFile` 或 `NBTRegionFile`。

## 编辑 Region 文件

通过 `NBTFile.openRegion` 可以把 1024 个固定 chunk 槽作为一棵可编辑树使用：

```java
try (NBTFile<ChunkRegion> file = NBTFile.openRegion(Path.of("r.0.0.mca"))) {
    NBTEditor<ChunkRegion> editor = file.getEditor();
    NBTAddress dataVersion = NBTAddress.root()
            .appendChunk(0)
            .appendChunkRoot()
            .appendName("DataVersion");
    editor.setScalar(editor.resolve(dataVersion), "3955");
    file.save();
}
```

Region 保存只排队与已提交基线不同的 chunk，并按 local index 提交。未修改 chunk 不会重写；已有 chunk 保留原压缩算法，新 chunk 默认使用 ZLIB。每个 chunk 的 payload 会先完整校验、序列化、压缩并写入新 sector，最后才切换 header。

需要直接控制 chunk 时可以使用 `NBTRegionFile`：

```java
try (NBTRegionFile region = NBTRegionFile.open(Path.of("r.0.0.mca"))) {
    Chunk chunk = region.readChunk(0); // detached 深拷贝
    CompoundTag root = chunk.getRootTag();
    if (root != null) {
        root.addInt("DataVersion", 3955);
        region.writeChunk(0, chunk);
        region.flush();
    }
}
```

`writeChunk` 和 `clearChunk` 只更新内存中的 pending 集合，`flush` 才发布。`close` 不会隐式保存 pending 修改。

## Region 失败恢复

多 chunk 保存中途失败时，`NBTPartialSaveException` 会给出 `committedIndexes()` 和 `failedIndex()`。
已提交 chunk 保持规范可读，失败及其后的 pending chunk 保留在会话中。使用 `NBTFile` 时应保留编辑器和文件会话，允许用户重试、继续修改或 undo；
下一次保存会把 pending 集合与当前编辑快照重新同步。

`NBTCommitUncertainException` 表示 header 发布后的可见状态无法可靠确认，或者 header 回滚失败。此时当前 Region 会话会拒绝继续读取和写入；调用方必须保留内存编辑、关闭会话并重新打开文件，重新发现磁盘上的完整状态后再决定下一步。

外部进程非协作写入、介质损坏或文件系统无法提供所需原子语义不在库的可修复范围内。XoyzNBT 会检测可观测冲突并失败关闭，不会用未校验数据覆盖源文件。
