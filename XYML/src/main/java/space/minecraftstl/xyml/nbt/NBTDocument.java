/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.nbt;

import org.glavo.nbt.NBTElement;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// One loaded NBT document with a mutable HelloNBT root and a stale-save baseline.
///
/// HelloNBT itself supplies the concrete mutation operations on tags, chunks, and regions. This
/// class intentionally does not invent a second editing API or claim that arbitrary textual input
/// can be converted safely. A caller that uses `rootElement` must obey HelloNBT's type and parent
/// invariants and must not mutate the root concurrently with `NBTDocumentService.save`.
@NotNullByDefault
public final class NBTDocument {
    /// Normalized absolute source path.
    private final Path file;

    /// Filename-derived container family.
    private final NBTFileType fileType;

    /// Exact outer encoding that save operations preserve.
    private final NBTStorageEncoding storageEncoding;

    /// Mutable in-memory HelloNBT root exposed as the library's actual editing boundary.
    private final NBTElement rootElement;

    /// Last successfully loaded or saved semantic root used to reject stale overwrites.
    private NBTElement baselineElement;

    /// Last successfully loaded or saved encoded source fingerprint.
    private NBTSourceFingerprint sourceFingerprint;

    /// Creates a loaded document and deep-copies the original semantic baseline.
    ///
    /// @param file normalized absolute source path
    /// @param fileType supported source family
    /// @param storageEncoding detected source encoding
    /// @param rootElement parsed mutable HelloNBT root
    /// @param sourceFingerprint stable encoded source fingerprint
    NBTDocument(
            Path file,
            NBTFileType fileType,
            NBTStorageEncoding storageEncoding,
            NBTElement rootElement,
            NBTSourceFingerprint sourceFingerprint) {
        this.file = Objects.requireNonNull(file, "file");
        this.fileType = Objects.requireNonNull(fileType, "fileType");
        this.storageEncoding = Objects.requireNonNull(storageEncoding, "storageEncoding");
        this.rootElement = Objects.requireNonNull(rootElement, "rootElement");
        baselineElement = rootElement.clone();
        this.sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
    }

    /// Returns the normalized absolute source path.
    ///
    /// @return source path
    public Path file() {
        return file;
    }

    /// Returns the filename-derived NBT container family.
    ///
    /// @return supported file type
    public NBTFileType fileType() {
        return fileType;
    }

    /// Returns the exact outer encoding that a save operation will preserve.
    ///
    /// @return detected storage encoding
    public NBTStorageEncoding storageEncoding() {
        return storageEncoding;
    }

    /// Returns the mutable root supplied by HelloNBT.
    ///
    /// No launcher-specific editing semantics are added here. Callers must use only operations
    /// supported by the concrete HelloNBT element type and must not mutate it during an asynchronous
    /// save.
    ///
    /// @return mutable HelloNBT root
    public NBTElement rootElement() {
        return rootElement;
    }

    /// Creates a fresh lazy structural view of the current in-memory root.
    ///
    /// A fresh view should be requested after a HelloNBT mutation because each node captures its
    /// name, scalar text, and direct-child count at construction time.
    ///
    /// @return new lazily materialized root node
    public NBTTreeNode rootNode() {
        @Nullable Path fileName = file.getFileName();
        String rootName = fileName == null ? file.toString() : fileName.toString();
        return new NBTTreeNode(rootElement, rootName);
    }

    /// Returns a deep snapshot of the current working root for one save transaction.
    ///
    /// @return detached HelloNBT root snapshot
    synchronized NBTElement snapshotElementForSave() {
        return rootElement.clone();
    }

    /// Returns the private semantic baseline without exposing it to application mutation.
    ///
    /// The caller holds this document's monitor for the complete save transaction.
    ///
    /// @return last loaded or saved root baseline
    synchronized NBTElement baselineElement() {
        return baselineElement;
    }

    /// Returns the encoded source fingerprint expected by the next save transaction.
    ///
    /// The caller holds this document's monitor for the complete save transaction.
    ///
    /// @return last loaded or saved source fingerprint
    synchronized NBTSourceFingerprint sourceFingerprint() {
        return sourceFingerprint;
    }

    /// Advances stale-save baselines after an atomic replacement succeeds.
    ///
    /// @param savedElement exact detached root written to disk
    /// @param savedFingerprint fingerprint of the atomically published temporary file
    synchronized void markSaved(NBTElement savedElement, NBTSourceFingerprint savedFingerprint) {
        baselineElement = Objects.requireNonNull(savedElement, "savedElement").clone();
        sourceFingerprint = Objects.requireNonNull(savedFingerprint, "savedFingerprint");
    }
}
