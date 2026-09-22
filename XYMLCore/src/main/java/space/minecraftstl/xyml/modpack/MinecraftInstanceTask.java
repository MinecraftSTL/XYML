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
package space.minecraftstl.xyml.modpack;

import kala.compress.archivers.zip.ZipArchiveEntry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.tree.ArchiveFileTree;
import space.minecraftstl.xyml.util.tree.ZipFileTree;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Builds and writes one modpack configuration by hashing selected entries from its input archive.
@NotNullByDefault
public final class MinecraftInstanceTask<T> extends Task<ModpackConfiguration<T>> {

    /// Input modpack archive.
    private final Path zipFile;

    /// Archive entry-name charset.
    private final Charset encoding;

    /// Immutable normalized archive subdirectory snapshot.
    private final @Unmodifiable List<String> subDirectories;

    /// Destination configuration file.
    private final Path jsonFile;

    /// Format-specific manifest stored in the generated configuration.
    private final T manifest;

    /// Stable modpack provider identifier.
    private final String type;

    /// Display name stored in the generated configuration.
    private final String name;

    /// Optional modpack version stored in the generated configuration.
    private final @Nullable String version;

    /// Creates a configuration task with exact input archive and output-file resources.
    ///
    /// @param zipFile input modpack archive
    /// @param encoding archive entry-name charset
    /// @param subDirectories archive subdirectories whose files become override metadata
    /// @param manifest format-specific manifest
    /// @param modpackProvider provider defining the stored type identifier
    /// @param name modpack display name
    /// @param version modpack version, or null when the format does not provide one
    /// @param jsonFile destination configuration file
    public MinecraftInstanceTask(
            Path zipFile,
            Charset encoding,
            List<String> subDirectories,
            T manifest,
            ModpackProvider modpackProvider,
            String name,
            @Nullable String version,
            Path jsonFile) {
        this.zipFile = zipFile;
        this.encoding = encoding;
        this.subDirectories = subDirectories.stream().map(FileUtils::normalizePath).toList();
        this.manifest = manifest;
        this.jsonFile = jsonFile;
        this.type = modpackProvider.getName();
        this.name = name;
        this.version = version;
        setResources(TaskResource.archive(zipFile), TaskResource.configuration(jsonFile));
    }

    private static void getOverrides(List<ModpackConfiguration.FileInformation> overrides,
                                     ZipFileTree tree,
                                     ArchiveFileTree.Dir<ZipArchiveEntry> dir,
                                     List<String> names) throws IOException {
        String prefix = String.join("/", names);
        if (!prefix.isEmpty())
            prefix = prefix + "/";

        for (Map.Entry<String, ZipArchiveEntry> entry : dir.getFiles().entrySet()) {
            String hash;
            try (InputStream input = tree.getInputStream(entry.getValue())) {
                hash = DigestUtils.digestToString("SHA-1", input);
            }
            overrides.add(new ModpackConfiguration.FileInformation(prefix + entry.getKey(), hash));
        }

        for (ArchiveFileTree.Dir<ZipArchiveEntry> subDir : dir.getSubDirs().values()) {
            names.add(subDir.getName());
            getOverrides(overrides, tree, subDir, names);
            names.remove(names.size() - 1);
        }
    }

    @Override
    public void execute() throws Exception {
        List<ModpackConfiguration.FileInformation> overrides = new ArrayList<>();

        try (var tree = new ZipFileTree(CompressingUtils.openZipFileWithPossibleEncoding(zipFile, encoding))) {
            for (String subDirectory : subDirectories) {
                ArchiveFileTree.Dir<ZipArchiveEntry> root = tree.getDirectory(subDirectory);
                if (root == null)
                    continue;
                var names = new ArrayList<String>();
                getOverrides(overrides, tree, root, names);
            }
        }
        ModpackConfiguration<T> configuration = new ModpackConfiguration<>(manifest, type, name, version, overrides);
        Files.createDirectories(jsonFile.getParent());
        JsonUtils.writeToJsonFile(jsonFile, configuration);
        setResult(configuration);
    }
}
