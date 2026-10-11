/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2024 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.util.tree;

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.zip.GZIPInputStream;

/// Owns a TAR reader and deferred directory hierarchy, including any decompressed temporary archive.
@NotNullByDefault
public final class TarFileTree extends ArchiveFileTree<TarArchiveReader, TarArchiveEntry> {

    public static TarFileTree open(Path file) throws IOException {
        String fileName = file.getFileName().toString();

        if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            Path tempFile = Files.createTempFile("xyml-", ".tar");
            TarArchiveReader tarFile;
            try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(file));
                 OutputStream output = Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)
            ) {
                input.transferTo(output);
                tarFile = new TarArchiveReader(tempFile);
            } catch (Throwable e) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Throwable e2) {
                    e.addSuppressed(e2);
                }
                throw e;
            }

            return new TarFileTree(tarFile, tempFile);
        } else {
            return new TarFileTree(new TarArchiveReader(file), null);
        }
    }

    /// Temporary decompressed TAR, or null for an original archive.
    private final @Nullable Path tempFile;

    /// Registered cleanup hook only when a temporary archive is owned.
    private final @Nullable Thread shutdownHook;

    /// Whether all owned resources and the temporary archive were released.
    private boolean released;

    /// Complete hierarchy published after successful initialization.
    private @Nullable Dir<TarArchiveEntry> root;

    /// Creates an owning TAR wrapper without building directory nodes.
    public TarFileTree(TarArchiveReader file, @Nullable Path tempFile) throws IOException {
        super(file);
        this.tempFile = tempFile;

        if (tempFile != null) {
            this.shutdownHook = new Thread(() -> {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException failure) {
                    space.minecraftstl.xyml.util.logging.Logger.LOG.warning(
                            "TAR temporary cleanup failed (" + failure.getClass().getSimpleName() + ")");
                }
            });
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (RuntimeException | Error registrationFailure) {
                try {
                    file.close();
                } catch (IOException closeFailure) {
                    registrationFailure.addSuppressed(closeFailure);
                }
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException deletionFailure) {
                    registrationFailure.addSuppressed(deletionFailure);
                }
                throw registrationFailure;
            }
        } else
            this.shutdownHook = null;
    }

    /// Validates and publishes the hierarchy only on first directory access.
    @Override
    public synchronized Dir<TarArchiveEntry> getRoot() throws IOException {
        requireOpen();
        if (root == null) {
            Dir<TarArchiveEntry> built = new Dir<>("", "");
            ArchiveEntryIndex.buildTree(reader.getEntries(), built);
            root = built;
        }
        return root;
    }

    @Override
    protected void copyAttributes(@NotNull TarArchiveEntry source, @NotNull Path targetFile) throws IOException {
        var fileAttributeView = Files.getFileAttributeView(targetFile, BasicFileAttributeView.class);
        if (fileAttributeView == null)
            return;

        fileAttributeView.setTimes(
                source.getLastModifiedTime(),
                source.getLastAccessTime(),
                source.getCreationTime()
        );
    }

    @Override
    public InputStream getInputStream(TarArchiveEntry entry) throws IOException {
        requireOpen();
        return reader.getInputStream(entry);
    }

    @Override
    public boolean isLink(TarArchiveEntry entry) {
        return entry.isSymbolicLink();
    }

    @Override
    public String getLink(TarArchiveEntry entry) throws IOException {
        return entry.getLinkName();
    }

    @Override
    public boolean isExecutable(TarArchiveEntry entry) {
        return entry.isFile() && (entry.getMode() & 0b1000000) != 0;
    }

    /// Closes owned resources and permits cleanup retry after an I/O failure.
    @Override
    public synchronized void close() throws IOException {
        markClosed();
        if (released) return;
        @Nullable IOException failure = null;
        try {
            reader.close();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException deletionFailure) {
                if (failure == null) failure = deletionFailure;
                else failure.addSuppressed(deletionFailure);
            }
            if (failure == null && shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException expected) {
                    // Shutdown has started; the registered hook owns any remaining cleanup.
                }
            }
        }
        if (failure != null) throw failure;
        released = true;
    }
}
