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
package org.glavo.nbt.tag;

import org.glavo.nbt.NBTParent;
import org.glavo.nbt.internal.input.DataReader;
import org.glavo.nbt.internal.output.DataWriter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

/// Internal access to the tag implementation.
///
/// This class is **NOT** a public API and should not be used directly.
@ApiStatus.Internal
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

}
