// SPDX-FileCopyrightText: Copyright (C) 2024 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.java;

import kala.compress.archivers.ArchiveEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.IOUtils;
import space.minecraftstl.xyml.util.tree.ArchiveFileTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Extracts one local Java archive into a stable managed runtime directory.
@NotNullByDefault
public final class JavaInstallTask extends Task<JavaManifest> {

    /// Final managed runtime directory.
    private final Path targetDir;

    /// Immutable manifest update metadata.
    private final @Unmodifiable Map<String, Object> update;

    /// Input Java archive.
    private final Path archiveFile;

    /// Manifest entries built while extracting the archive.
    private final Map<String, JavaLocalFiles.Local> files = new LinkedHashMap<>();

    /// Archive-relative path segments for the current recursive entry.
    private final ArrayList<String> nameStack = new ArrayList<>();

    /// Reusable archive copy buffer.
    private final byte[] buffer = new byte[IOUtils.DEFAULT_BUFFER_SIZE];

    /// SHA-1 digest required by the managed Java manifest format.
    private final MessageDigest messageDigest = DigestUtils.getDigest("SHA-1");

    /// Creates a stopped local Java archive extraction task.
    ///
    /// @param targetDir final managed runtime directory
    /// @param update immutable manifest update metadata
    /// @param archiveFile input Java archive
    public JavaInstallTask(Path targetDir, @Unmodifiable Map<String, Object> update, Path archiveFile) {
        this.targetDir = Objects.requireNonNull(targetDir, "targetDir").toAbsolutePath().normalize();
        this.update = Map.copyOf(Objects.requireNonNull(update, "update"));
        this.archiveFile = Objects.requireNonNull(archiveFile, "archiveFile").toAbsolutePath().normalize();
        setResources(TaskResource.javaRuntime(this.targetDir), TaskResource.archive(this.archiveFile));
    }

    /// Extracts the archive and records its Java metadata and file manifest.
    @Override
    public void execute() throws Exception {
        JavaInfo info;

        try (ArchiveFileTree<?, ?> tree = ArchiveFileTree.open(archiveFile)) {
            info = JavaInfo.fromArchive(tree);
            copyDirContent(tree, targetDir);
        }

        setResult(new JavaManifest(info, update, files));
    }

    /// Copies the sole Java Home directory from an opened archive.
    ///
    /// @param tree opened Java archive tree
    /// @param targetDir extraction destination
    /// @param <F> archive format type
    /// @param <E> archive entry type
    /// @throws IOException when archive contents cannot be copied
    private <F, E extends ArchiveEntry> void copyDirContent(
            ArchiveFileTree<F, E> tree,
            Path targetDir) throws IOException {
        copyDirContent(tree, tree.getRoot().getSubDirs().values().iterator().next(), targetDir);
    }

    /// Recursively copies one archive directory and records each manifest entry.
    ///
    /// @param tree opened Java archive tree
    /// @param dir current archive directory
    /// @param targetDir current extraction destination
    /// @param <F> archive format type
    /// @param <E> archive entry type
    /// @throws IOException when archive contents cannot be copied
    private <F, E extends ArchiveEntry> void copyDirContent(
            ArchiveFileTree<F, E> tree,
            ArchiveFileTree.Dir<E> dir,
            Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        for (Map.Entry<String, E> pair : dir.getFiles().entrySet()) {
            Path path = targetDir.resolve(pair.getKey());
            E entry = pair.getValue();

            nameStack.add(pair.getKey());
            if (tree.isLink(entry)) {
                String linkTarget = tree.getLink(entry);
                files.put(String.join("/", nameStack), new JavaLocalFiles.LocalLink(linkTarget));
                Files.createSymbolicLink(path, Paths.get(linkTarget));
            } else {
                long size = 0L;

                try (InputStream input = tree.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(
                             path,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING)) {
                    messageDigest.reset();

                    int c;
                    while ((c = input.read(buffer)) > 0) {
                        size += c;
                        output.write(buffer, 0, c);
                        messageDigest.update(buffer, 0, c);
                    }
                }

                if (tree.isExecutable(entry))
                    FileUtils.setExecutable(path);

                files.put(
                        String.join("/", nameStack),
                        new JavaLocalFiles.LocalFile(
                                HexFormat.of().formatHex(messageDigest.digest()),
                                size));
            }
            nameStack.remove(nameStack.size() - 1);
        }

        for (Map.Entry<String, ArchiveFileTree.Dir<E>> pair : dir.getSubDirs().entrySet()) {
            nameStack.add(pair.getKey());
            files.put(String.join("/", nameStack), new JavaLocalFiles.LocalDirectory());
            copyDirContent(tree, pair.getValue(), targetDir.resolve(pair.getKey()));
            nameStack.remove(nameStack.size() - 1);
        }
    }
}
