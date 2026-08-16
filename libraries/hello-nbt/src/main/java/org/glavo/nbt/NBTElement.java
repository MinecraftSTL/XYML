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

import org.glavo.nbt.chunk.Chunk;
import org.glavo.nbt.chunk.ChunkRegion;
import org.glavo.nbt.tag.Tag;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Base interface for all NBT elements.
///
/// An NBT element may be a child element of an [NBTParent] (such as a [CompoundTag][org.glavo.nbt.tag.CompoundTag] or [ListTag][org.glavo.nbt.tag.ListTag]).
///
/// Each NBT element has a unique position in the entire tree:
/// It can have at most one parent and cannot belong to multiple parents simultaneously;
/// and it cannot be multiple children of the same parent at the same time.
///
/// @see Tag
/// @see ChunkRegion
/// @see Chunk
public sealed interface NBTElement permits ChunkRegion, Chunk, Tag, NBTParent {
    /// Returns the parent of this element, or `null` if this element is not a child of any parent.
    @Contract(pure = true)
    @Nullable NBTParent<?> getParent();

    /// Returns `true` if this element is the root element of an NBT structure, i.e. it has no parent.
    @Contract(pure = true)
    default boolean isRoot() {
        return getParent() == null;
    }

    /// Returns the root element of this element.
    @Contract(pure = true)
    default NBTElement getRoot() {
        NBTElement element = this;
        while (element.getParent() != null) {
            element = element.getParent();
        }
        return element;
    }

    /// Returns a clone of this element.
    ///
    /// This method always performs a deep copy.
    ///
    /// The returned element has the same content but not in a parent element.
    @Contract(value = "-> new", pure = true)
    NBTElement clone();
}
