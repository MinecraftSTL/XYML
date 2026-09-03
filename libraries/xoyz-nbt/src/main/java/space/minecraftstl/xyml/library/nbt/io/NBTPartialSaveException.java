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
// Added by MinecraftSTL in 2026 for transactional XoyzNBT region saves.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/// Reports that a multi-chunk region flush committed only a prefix of its pending chunks.
///
/// A failed flush never rolls back chunks which were already published. The indexes in
/// [#committedIndexes()] identify those chunks. When [#failedIndex()] is nonnegative, that chunk
/// and all later pending chunks stay dirty. A value of `-1` reports post-commit work which failed
/// after every listed chunk became durable.
@NotNullByDefault
public final class NBTPartialSaveException extends IOException {
    /// Serialization identifier for the stable checked exception type.
    private static final long serialVersionUID = 1L;

    /// Immutable ascending prefix of local indexes published before the failure.
    private final @Unmodifiable List<Integer> committedIndexes;
    /// Local index whose publication failed, or `-1` after a post-commit failure.
    private final int failedIndex;

    /// Creates a partial-save exception.
    ///
    /// @param committedIndexes indexes successfully published before the failure
    /// @param failedIndex index whose publication failed, or `-1` when no single index is known
    /// @param cause underlying I/O or validation failure
    public NBTPartialSaveException(List<Integer> committedIndexes, int failedIndex, Throwable cause) {
        super("Region save committed " + committedIndexes.size() + " chunk(s); failed at index " + failedIndex,
                Objects.requireNonNull(cause, "cause"));
        this.committedIndexes = List.copyOf(Objects.requireNonNull(committedIndexes, "committedIndexes"));
        this.failedIndex = failedIndex;
    }

    /// Returns the local indexes committed before the failure.
    public @Unmodifiable List<Integer> committedIndexes() {
        return committedIndexes;
    }

    /// Returns the local index which failed, or `-1` if no index was identified.
    public int failedIndex() {
        return failedIndex;
    }
}
