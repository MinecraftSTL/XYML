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
import space.minecraftstl.xyml.library.nbt.io.NBTFile;
import space.minecraftstl.xyml.library.nbt.io.NBTSaveOptions;
import space.minecraftstl.xyml.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Dispatches safe XoyzNBT file-session operations and applies launcher filename policy.
///
/// Compression detection, strict parsing, structural validation, fingerprints, staging, region
/// copy-on-write publication, and savepoint handling all remain inside [NBTFile]. This launcher
/// service only chooses the tag or region entry point and selects the conventional `.dat_old`
/// rolling backup for a main `.dat` file.
@NotNullByDefault
public final class NBTDocumentService {
    /// Executor that owns all blocking NBT and filesystem operations.
    private final Executor ioExecutor;

    /// Creates a service using the launcher's shared I/O scheduler.
    public NBTDocumentService() {
        this(Schedulers.io());
    }

    /// Creates a service whose operations are dispatched to the supplied executor.
    ///
    /// The executor remains caller-owned and is never shut down by this service.
    ///
    /// @param ioExecutor executor for blocking file-session work
    public NBTDocumentService(Executor ioExecutor) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /// Opens one supported NBT file on the configured background executor.
    ///
    /// The future fails with `IOException` as its completion cause for unsupported extensions,
    /// invalid complete input, stale source state, or invalid region storage.
    ///
    /// @param file candidate source path
    /// @return future loaded document
    public CompletableFuture<NBTDocument> open(Path file) {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        CompletableFuture<NBTDocument> result = new CompletableFuture<>();
        ioExecutor.execute(() -> {
            @Nullable NBTDocument document = null;
            try {
                document = openOnExecutor(normalized);
                if (!result.complete(document)) {
                    closeAfterCancelledOpen(document);
                }
            } catch (IOException | RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /// Saves one document through its safe XoyzNBT file session on the background executor.
    ///
    /// Standalone files ending in `.dat` receive a single rolling sibling backup ending in
    /// `.dat_old`. Files already ending in `.dat_old`, `.nbt`, and region files do not recursively
    /// create backup history.
    ///
    /// @param document open document
    /// @return future completed after publication and force operations finish
    public CompletableFuture<Void> save(NBTDocument document) {
        NBTDocument selected = Objects.requireNonNull(document, "document");
        return CompletableFuture.runAsync(() -> {
            try {
                selected.saveFromService(saveOptions(selected));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, ioExecutor);
    }

    /// Runs one non-null in-memory document operation on the configured background executor.
    ///
    /// The operation remains responsible for synchronizing access to its document. This scheduling
    /// boundary lets the Swing controller keep deep copies, strict SNBT parsing, and whole-tree
    /// validation off the event dispatch thread without learning which executor XYML owns.
    ///
    /// @param operation non-null result supplier
    /// @param <T> result type
    /// @return future operation result
    public <T> CompletableFuture<T> supplyAsync(Supplier<? extends T> operation) {
        Supplier<? extends T> selected = Objects.requireNonNull(operation, "operation");
        return CompletableFuture.supplyAsync(
                () -> Objects.requireNonNull(selected.get(), "operation result"),
                ioExecutor);
    }

    /// Selects the library entry point for one normalized source.
    ///
    /// @param file normalized absolute path
    /// @return lifecycle-bound launcher document
    /// @throws IOException if type detection or the selected strict open fails
    private static NBTDocument openOnExecutor(Path file) throws IOException {
        @Nullable NBTFileType fileType = NBTFileType.detect(file);
        if (fileType == null) {
            throw new IOException("Unsupported NBT file extension: " + file);
        }
        validateExistingSource(file);
        NBTFile<? extends NBTElement> session = switch (fileType) {
            case TAG -> NBTFile.openTag(file);
            case ANVIL, REGION -> NBTFile.openRegion(file);
        };
        return new NBTDocument(fileType, session);
    }

    /// Rejects missing, special, and symbolic-link sources before a region open can create or follow them.
    ///
    /// @param file normalized candidate source
    /// @throws IOException if the source is not an existing regular non-link file
    private static void validateExistingSource(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("NBT source is not a regular non-link file: " + file);
        }
    }

    /// Selects the rolling-backup policy for one launcher document.
    ///
    /// @param document document about to be saved
    /// @return immutable generic XoyzNBT save options
    private static NBTSaveOptions saveOptions(NBTDocument document) {
        if (document.fileType() != NBTFileType.TAG) {
            return NBTSaveOptions.withoutBackup();
        }
        Path file = document.file();
        @Nullable Path fileName = file.getFileName();
        if (fileName == null) {
            return NBTSaveOptions.withoutBackup();
        }
        String name = fileName.toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".dat")) {
            return NBTSaveOptions.withoutBackup();
        }
        @Nullable Path parent = file.getParent();
        if (parent == null) {
            return NBTSaveOptions.withoutBackup();
        }
        String backupName = name + "_old";
        return NBTSaveOptions.withBackup(parent.resolve(backupName));
    }

    /// Releases a region channel when its open future was cancelled before publication.
    ///
    /// @param document successfully opened document rejected by the cancelled future
    private static void closeAfterCancelledOpen(NBTDocument document) {
        try {
            document.close();
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Failed to close an NBT document after its open operation was cancelled", failure);
        }
    }
}
