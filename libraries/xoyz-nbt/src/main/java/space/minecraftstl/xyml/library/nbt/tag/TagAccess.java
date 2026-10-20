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
package space.minecraftstl.xyml.library.nbt.tag;

import space.minecraftstl.xyml.library.nbt.NBTParent;
import space.minecraftstl.xyml.library.nbt.internal.input.DataReader;
import space.minecraftstl.xyml.library.nbt.internal.output.DataWriter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

/// Internal access to the tag implementation.
///
/// This class is **NOT** a public API and should not be used directly.
@ApiStatus.Internal
@NotNullByDefault
public final class TagAccess {
    private static final TagAccess INSTANCE = new TagAccess();

    /// Get an instance of the internal operations.
    ///
    /// @param lookup A lookup object used to check whether a user has access rights to the [Tag].
    /// @return An instance of the internal operations.
    /// @throws UnsupportedOperationException if the user does not have access rights to the [Tag].
    public static TagAccess getInstance(MethodHandles.Lookup lookup) {
        try {
            MethodHandles.privateLookupIn(Tag.class, lookup);
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException(e);
        }
        return INSTANCE;
    }

    private TagAccess() {
    }

    public void setParent(Tag tag, @Nullable NBTParent<? extends Tag> parent, int index) {
        tag.setParent(parent, index);
    }

    public void readContent(Tag tag, DataReader reader) throws IOException {
        tag.readContent(reader);
    }

    public void writeContent(Tag tag, DataWriter writer) throws IOException {
        tag.writeContent(writer);
    }

    /// Returns the internal value of the tag without cloning.
    public <A> A getInternalArray(ArrayTag<?, ?, A, ?> tag) {
        return tag.values;
    }

    /// Validates that a compound's private name index has one entry per ordered child.
    ///
    /// @param tag compound whose internal index is checked
    /// @throws IllegalArgumentException if the index cardinality is inconsistent
    public void validateCompoundNameIndexSize(CompoundTag tag) {
        tag.validateNameIndexSize();
    }

    /// Returns the allocated length of a primitive array's lazy child-tag storage.
    ///
    /// @param tag primitive array whose storage is inspected
    /// @return allocated lazy child-tag capacity
    public int getArrayTagStorageLength(ArrayTag<?, ?, ?, ?> tag) {
        return tag.tags.length;
    }

    /// Returns one already materialized primitive-array child without creating it.
    ///
    /// @param tag primitive array whose lazy storage is inspected
    /// @param index storage index below [#getArrayTagStorageLength(ArrayTag)]
    /// @return materialized child, or `null` when the value has no wrapper object
    public @Nullable Tag getMaterializedArrayTag(ArrayTag<?, ?, ?, ?> tag, int index) {
        return tag.tags[index];
    }

}
