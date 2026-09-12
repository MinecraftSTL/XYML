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

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.io.NBTFile;
import space.minecraftstl.xyml.library.nbt.io.NBTFileEncoding;
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.io.NBTSaveOptions;
import space.minecraftstl.xyml.library.nbt.io.StorageProfile;
import space.minecraftstl.xyml.library.nbt.io.StorageProfileChange;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// One lifecycle-bound XYML view of a safe XoyzNBT file session.
///
/// The document exposes the library's revision-aware [NBTEditor] as its only mutable boundary.
/// Every compatibility snapshot is detached, so launcher code cannot bypass editor validation,
/// history, stale-node checks, or file-session savepoints by retaining a raw working-tree object.
@NotNullByDefault
public final class NBTDocument implements AutoCloseable {
    /// Filename-derived container family used by launcher presentation code.
    private final NBTFileType fileType;

    /// Safe XoyzNBT session which owns the editor, read report, and region channel.
    private final NBTFile<? extends NBTElement> fileSession;

    /// Whether this launcher document has released its file session.
    private boolean closed;

    /// Package-owned callback notified exactly once after the physical file session closes successfully, or null when
    /// unmanaged. A failed close leaves this callback installed so the owning service can retry the same session.
    private @Nullable Consumer<@Nullable Throwable> closedListener;

    /// Creates a launcher document around one successfully opened XoyzNBT session.
    ///
    /// @param fileType supported filename-derived family
    /// @param fileSession open safe file session
    NBTDocument(NBTFileType fileType, NBTFile<? extends NBTElement> fileSession) {
        this.fileType = Objects.requireNonNull(fileType, "fileType");
        this.fileSession = Objects.requireNonNull(fileSession, "fileSession");
    }

    /// Returns the normalized absolute source path.
    ///
    /// @return source path
    public Path file() {
        return fileSession.getPath();
    }

    /// Returns the filename-derived NBT container family.
    ///
    /// @return supported file type
    public NBTFileType fileType() {
        return fileType;
    }

    /// Returns the exact XoyzNBT file encoding preserved by save operations.
    ///
    /// @return detected file encoding
    public NBTFileEncoding encoding() {
        return fileSession.getEncoding();
    }

    /// Returns the legacy launcher encoding view of [#encoding()].
    ///
    /// This compatibility method performs no detection of its own; the value is mapped directly
    /// from the encoding established by [NBTFile] during open.
    ///
    /// @return detected storage encoding
    public NBTStorageEncoding storageEncoding() {
        return NBTStorageEncoding.fromFileEncoding(encoding());
    }

    /// Returns immutable diagnostics captured while opening this document.
    ///
    /// A clean report is returned for a source accepted by the strict reader. A recovered or partial report remains
    /// attached to the document for its whole lifetime so the UI can keep the warning visible and require explicit
    /// confirmation before saving a source with confirmed partial data loss.
    ///
    /// @return immutable tolerant-read report
    public NBTReadReport readReport() {
        return fileSession.readReport();
    }

    /// Returns the immutable compression and region-marker profile captured by this session.
    ///
    /// Region profiles retain all 1024 slot markers, external flags, and occupancy bits so the
    /// editor can explain a damaged slot without re-reading or guessing its on-disk envelope.
    ///
    /// @return standalone encoding or complete region storage profile
    public StorageProfile storageProfile() {
        return fileSession.storageProfile();
    }

    /// Returns storage-profile changes emitted by the most recent region save.
    ///
    /// Standalone documents return an empty list. Region documents retain immutable before/after
    /// slot snapshots so the Swing surface can explain an inline-to-external transition and its
    /// companion-file consequence.
    ///
    /// @return immutable profile-change snapshot in publication order
    public @Unmodifiable List<StorageProfileChange> storageProfileChanges() {
        return fileSession.storageProfileChanges();
    }

    /// Returns whether the source needs an explicit strict repair save.
    ///
    /// @return whether the open report contains a recovery or data-loss diagnostic
    public boolean requiresRepair() {
        return readReport().requiresRepair();
    }

    /// Returns the revision-aware editor owned by this document.
    ///
    /// @return safe mutable editing boundary
    /// @throws IllegalStateException if this document is closed
    public synchronized NBTEditor<? extends NBTElement> editor() {
        ensureOpen();
        return fileSession.getEditor();
    }

    /// Returns the revision-aware editor owned by this document.
    ///
    /// @return safe mutable editing boundary
    /// @throws IllegalStateException if this document is closed
    public NBTEditor<? extends NBTElement> getEditor() {
        return editor();
    }

    /// Returns a detached deep snapshot of the current root.
    ///
    /// Mutating the returned object never changes this document. Call [#editor()] for edits that
    /// should participate in validation, undo/redo, dirty tracking, and saving.
    ///
    /// @return detached mutable root snapshot
    /// @throws IllegalStateException if this document is closed
    public synchronized NBTElement rootSnapshot() {
        ensureOpen();
        return fileSession.getEditor().snapshot();
    }

    /// Returns a detached deep snapshot of the current root for compatibility with older callers.
    ///
    /// Unlike the former implementation, this method never exposes the editor's working tree.
    ///
    /// @return detached mutable root snapshot
    /// @throws IllegalStateException if this document is closed
    public NBTElement rootElement() {
        return rootSnapshot();
    }

    /// Returns a detached deep snapshot at one current address.
    ///
    /// @param address immutable node address
    /// @return detached node content
    /// @throws NBTEditException if no node exists at the address
    /// @throws IllegalStateException if this document is closed
    public synchronized NBTElement snapshot(NBTAddress address) throws NBTEditException {
        ensureOpen();
        return fileSession.getEditor().snapshot(Objects.requireNonNull(address, "address"));
    }

    /// Creates a fresh lazy structural view at the editor's current revision.
    ///
    /// A fresh view must be requested after every successful edit because its immutable node
    /// handles intentionally become stale when the editor revision changes.
    ///
    /// @return lazily materialized root metadata
    /// @throws IllegalStateException if this document is closed
    public synchronized NBTTreeNode rootNode() {
        ensureOpen();
        @Nullable Path fileName = file().getFileName();
        String rootName = fileName == null ? file().toString() : fileName.toString();
        return NBTTreeNode.forDocument(fileSession.getEditor(), rootName, fileType);
    }

    /// Returns whether the editor differs from its last successfully published savepoint.
    ///
    /// @return current editor dirty state
    /// @throws IllegalStateException if this document is closed
    public synchronized boolean isDirty() {
        ensureOpen();
        return fileSession.isDirty();
    }

    /// Saves through the underlying safe XoyzNBT session.
    ///
    /// @param options backup and publication options selected by the launcher
    /// @throws IOException if strict serialization or publication fails
    private synchronized void save(NBTSaveOptions options) throws IOException {
        ensureOpen();
        fileSession.save(Objects.requireNonNull(options, "options"));
    }

    /// Invokes the private save boundary for the package-local asynchronous service.
    ///
    /// @param options launcher-selected save options
    /// @throws IOException if the safe file session rejects or cannot publish the save
    void saveFromService(NBTSaveOptions options) throws IOException {
        save(options);
    }

    /// Installs the asynchronous service callback which releases this document's task resource lease.
    ///
    /// The listener is cleared before invocation and is therefore notified at most once after successful closure,
    /// including when callers use this document directly in a try-with-resources statement. A failed close does not
    /// clear it, because the underlying file session remains open and can be retried.
    ///
    /// @param listener package-owned close listener
    /// @throws IllegalStateException if this document is closed or already managed
    synchronized void setClosedListener(Consumer<@Nullable Throwable> listener) {
        ensureOpen();
        if (closedListener != null) {
            throw new IllegalStateException("NBT document already has a close listener");
        }
        closedListener = Objects.requireNonNull(listener, "listener");
    }

    /// Releases any region channel without publishing pending in-memory edits.
    ///
    /// Standalone sessions have no persistent channel, but are closed as well so a late save
    /// cannot publish after the owning UI document has been discarded.
    ///
    /// @throws IOException if the underlying region channel cannot close
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        // Do not release the service lease on a failed physical close. The same document can be
        // retried after the underlying channel or sidecar cleanup becomes available.
        fileSession.close();
        closed = true;
        @Nullable Consumer<@Nullable Throwable> listener = closedListener;
        closedListener = null;
        if (listener != null) {
            listener.accept(null);
        }
    }

    /// Reports whether this document has released its file session.
    ///
    /// @return whether the document is closed
    public synchronized boolean isClosed() {
        return closed;
    }

    /// Rejects operations after lifecycle closure.
    ///
    /// @throws IllegalStateException if this document is closed
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("NBT document is closed");
        }
    }
}
