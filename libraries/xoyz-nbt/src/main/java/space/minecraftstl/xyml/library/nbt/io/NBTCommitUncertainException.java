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
// Added by MinecraftSTL in 2026 for recoverable XoyzNBT commit-state reporting.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/// Reports that a region header write and its attempted rollback both failed.
///
/// The current session refuses further reads and writes. Callers must retain
/// their in-memory edits, close the session, and reopen the file to discover which complete
/// header state is visible before deciding whether to retry.
@NotNullByDefault
public final class NBTCommitUncertainException extends IOException {
    /// Region whose visible header state must be rediscovered.
    private final Path path;

    /// Local chunk slot being published when the rollback failed.
    private final int localIndex;

    /// Creates an uncertain-state failure.
    ///
    /// @param path affected region path
    /// @param localIndex affected local chunk slot
    /// @param cause failure which prevented the committed state from being rediscovered
    public NBTCommitUncertainException(Path path, int localIndex, IOException cause) {
        super("Region commit state is uncertain for local index " + localIndex + ": " + path,
                Objects.requireNonNull(cause, "cause"));
        this.path = Objects.requireNonNull(path, "path");
        this.localIndex = localIndex;
    }

    /// Creates an uncertain-state failure after both publication and rollback fail.
    ///
    /// @param path affected region path
    /// @param localIndex affected local chunk slot
    /// @param publicationFailure original header-publication failure
    /// @param rollbackFailure failure while restoring the prior header
    public NBTCommitUncertainException(
            Path path,
            int localIndex,
            IOException publicationFailure,
            IOException rollbackFailure) {
        this(path, localIndex, Objects.requireNonNull(publicationFailure, "publicationFailure"));
        addSuppressed(Objects.requireNonNull(rollbackFailure, "rollbackFailure"));
    }

    /// Returns the affected region path.
    ///
    /// @return normalized region path
    public Path path() {
        return path;
    }

    /// Returns the affected local chunk slot.
    ///
    /// @return local index from 0 through 1023
    public int localIndex() {
        return localIndex;
    }
}
