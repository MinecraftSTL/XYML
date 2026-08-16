/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/// [HelloNBT](https://github.com/HMCL-dev/HelloNBT) is a modern Java library for reading and writing Minecraft NBT files.
///
/// For an introduction and detailed tutorials on HelloNBT, please refer to the [HelloNBT Documentation](https://github.com/HMCL-dev/HelloNBT/tree/main/docs).
///
/// If you want to learn about the HelloNBT API by reading the Javadoc, check the documentation of the following core classes:
///
/// - [org.glavo.nbt.tag.Tag]: Represents a tag in NBT format. Its documentation records the entire inheritance hierarchy of NBT tags.
/// - [org.glavo.nbt.chunk.Chunk]: Represents a chunk of NBT data.
/// - [org.glavo.nbt.chunk.ChunkRegion]: Represents a region of chunks.
/// - [org.glavo.nbt.io.NBTCodec]: The core class for reading and writing NBT data.
/// - [org.glavo.nbt.io.SNBTCodec]: The core class for reading and writing SNBT data.
module org.glavo.nbt {
    requires static org.jetbrains.annotations;
    requires static org.lz4.java;

    exports org.glavo.nbt;
    exports org.glavo.nbt.chunk;
    exports org.glavo.nbt.tag;
    exports org.glavo.nbt.io;
    exports org.glavo.nbt.validation;
}