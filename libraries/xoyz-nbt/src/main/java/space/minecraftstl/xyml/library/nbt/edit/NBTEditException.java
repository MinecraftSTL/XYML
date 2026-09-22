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
// Added by MinecraftSTL in 2026 for the generic XoyzNBT editing API.
package space.minecraftstl.xyml.library.nbt.edit;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Checked failure raised by a transactional NBT edit.
@NotNullByDefault
public final class NBTEditException extends Exception {
    /// Stable machine-readable reasons for rejected edits.
    public enum Reason {
        /// The node was created by an older editor revision.
        STALE_NODE,

        /// The node belongs to another editor session.
        FOREIGN_NODE,

        /// The requested address or child does not exist.
        NOT_FOUND,

        /// A supplied child index is outside its container.
        INVALID_INDEX,

        /// The selected element cannot be used for this operation.
        INVALID_TARGET,

        /// A compound name is empty or otherwise invalid.
        INVALID_NAME,

        /// A compound name would collide with another child.
        DUPLICATE_NAME,

        /// The requested operation would change an element type or list shape.
        TYPE_MISMATCH,

        /// The requested operation would create a cycle or shared ownership.
        CYCLE,

        /// The operation is not allowed on a root element.
        ROOT_OPERATION,

        /// The supplied data does not satisfy an NBT structural rule.
        INVALID_FORMAT,

        /// No undo entry is available.
        NO_UNDO,

        /// No redo entry is available.
        NO_REDO
    }

    private final Reason reason;

    /// Creates an edit exception with a stable reason and message.
    ///
    /// @param reason stable failure reason
    /// @param message human-readable detail
    public NBTEditException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /// Creates an edit exception with a stable reason and underlying cause.
    ///
    /// @param reason stable failure reason
    /// @param message human-readable detail
    /// @param cause underlying implementation failure
    public NBTEditException(Reason reason, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /// Returns the stable machine-readable reason.
    ///
    /// @return failure reason
    public Reason reason() {
        return reason;
    }

    /// Returns the stable machine-readable reason.
    ///
    /// @return failure reason
    public Reason getReason() {
        return reason;
    }
}
