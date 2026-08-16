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
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.

/// [HelloNBT](https://github.com/HMCL-dev/HelloNBT) is a modern Java library for reading and writing Minecraft NBT files.
///
/// For an introduction and detailed tutorials on HelloNBT, please refer to the [HelloNBT Documentation](https://github.com/HMCL-dev/HelloNBT/tree/main/docs).
///
/// If you want to learn about the HelloNBT API by reading the Javadoc, check the documentation of the following core classes:
///
/// - [space.minecraftstl.xyml.library.nbt.tag.Tag]: Represents a tag in NBT format. Its documentation records the entire inheritance hierarchy of NBT tags.
/// - [space.minecraftstl.xyml.library.nbt.chunk.Chunk]: Represents a chunk of NBT data.
/// - [space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion]: Represents a region of chunks.
/// - [space.minecraftstl.xyml.library.nbt.io.NBTCodec]: The core class for reading and writing NBT data.
/// - [space.minecraftstl.xyml.library.nbt.io.SNBTCodec]: The core class for reading and writing SNBT data.
module space.minecraftstl.xyml.library.nbt {
    requires static org.jetbrains.annotations;
    requires static org.lz4.java;

    exports space.minecraftstl.xyml.library.nbt;
    exports space.minecraftstl.xyml.library.nbt.chunk;
    exports space.minecraftstl.xyml.library.nbt.tag;
    exports space.minecraftstl.xyml.library.nbt.io;
    exports space.minecraftstl.xyml.library.nbt.validation;
}
