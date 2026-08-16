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
package org.glavo.nbt;

import org.glavo.nbt.internal.path.NBTPathImpl;
import org.glavo.nbt.internal.snbt.SNBTParser;
import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.tag.TagType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/// An NBT path is a descriptive string used to specify one or more particular elements from an NBT data tree.
///
/// @see <a href="https://minecraft.wiki/w/NBT_path">NBT Path - Minecraft Wiki</a>
/// @see NBTParent#getAllTags(NBTPath)
public sealed interface NBTPath<T extends Tag> permits NBTPathImpl {

    /// Parse a NBT path from a string.
    @Contract(pure = true)
    static NBTPath<?> of(String path) throws IllegalArgumentException {
        return new SNBTParser(path, 0, path.length()).nextPath();
    }

    /// Returns the tag type of this path.
    @Contract(pure = true)
    @Nullable TagType<T> getTagType();

    /// Returns a new path with the given tag type.
    ///
    /// Certain paths have a fixed tag type; for example, `{}`, `a.b{}``, and ``[{}]` can only match compound tags.
    /// For these NBTPaths, you must use the corresponding tag type, otherwise an `IllegalStateException` will be thrown.
    ///
    /// In contrast, other paths such as `a.b`, `a[0]`, and `a[]` do not have a fixed tag type.
    /// By default, [#getTagType()] will return null, allowing you to attach any tag type using this method.
    ///
    /// @throws IllegalStateException if the path does not match the given tag type.
    @Contract(pure = true)
    <T2 extends Tag> NBTPath<T2> withTagType(TagType<T2> tagType) throws IllegalStateException;

}
