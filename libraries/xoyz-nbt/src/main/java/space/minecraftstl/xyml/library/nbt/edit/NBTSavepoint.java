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
// Added by MinecraftSTL in 2026 for revision-aware XoyzNBT save sessions.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable detached content captured for a save operation.
///
/// A savepoint carries the editor session identity and revision internally. Callers can safely
/// hand the detached root to an I/O task and later ask the editor to commit the save only if no
/// newer edit has arrived.
///
/// @param <E> root element type
@NotNullByDefault
public final class NBTSavepoint<E extends NBTElement> {
    private final Object session;
    private final long revision;
    private final E root;

    NBTSavepoint(Object session, long revision, E root) {
        this.session = Objects.requireNonNull(session, "session");
        this.revision = revision;
        this.root = Objects.requireNonNull(root, "root");
    }

    /// Returns the revision represented by this savepoint.
    public long revision() {
        return revision;
    }

    /// Returns the detached root captured by this savepoint.
    ///
    /// The returned value is a defensive copy and can be freely modified by the writer.
    @SuppressWarnings("unchecked")
    public E root() {
        return (E) root.clone();
    }

    Object session() {
        return session;
    }
}
