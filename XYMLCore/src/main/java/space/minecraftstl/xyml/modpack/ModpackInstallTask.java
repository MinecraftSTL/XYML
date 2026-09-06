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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.io.Unzipper;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/// Extracts selected archive subdirectories into one modpack run directory while preserving user overrides.
@NotNullByDefault
public class ModpackInstallTask<T> extends Task<Void> {

    /// Input modpack archive.
    private final Path modpackFile;

    /// Destination directory receiving extracted overrides.
    private final Path dest;

    /// Archive entry-name charset.
    private final Charset charset;

    /// Immutable archive subdirectory snapshot.
    private final @Unmodifiable List<String> subDirectories;

    /// Immutable previous override metadata snapshot.
    private final @Unmodifiable List<ModpackConfiguration.FileInformation> overrides;

    /// Filter deciding which normalized archive entries may be extracted.
    private final Predicate<String> callback;

    /// Creates an archive extraction task with explicit input and destination resources.
    ///
    /// @param modpackFile      a zip file
    /// @param dest             destination to store unpacked files
    /// @param charset          charset of the zip file
    /// @param subDirectories   the subdirectory of zip file to unpack
    /// @param callback         test whether the file (given full path) in zip file should be unpacked or not
    /// @param oldConfiguration old modpack information if upgrade
    public ModpackInstallTask(
            Path modpackFile,
            Path dest,
            Charset charset,
            List<String> subDirectories,
            Predicate<String> callback,
            @Nullable ModpackConfiguration<T> oldConfiguration) {
        this.modpackFile = modpackFile;
        this.dest = dest;
        this.charset = charset;
        this.subDirectories = List.copyOf(subDirectories);
        this.callback = callback;

        if (oldConfiguration == null)
            overrides = List.of();
        else
            overrides = List.copyOf(oldConfiguration.getOverrides());

        setResources(TaskResource.gameDirectory(dest), TaskResource.archive(modpackFile));
    }

    @Override
    public void execute() throws Exception {
        Set<String> entries = new HashSet<>();
        Files.createDirectories(dest);

        HashMap<String, ModpackConfiguration.FileInformation> files = new HashMap<>();
        for (ModpackConfiguration.FileInformation file : overrides)
            files.put(file.getPath(), file);


        for (String subDirectory : subDirectories) {
            new Unzipper(modpackFile, dest)
                    .setSubDirectory(subDirectory)
                    .setTerminateIfSubDirectoryNotExists()
                    .setReplaceExistentFile(true)
                    .setEncoding(charset)
                    .setFilter((zipEntry, destFile, relativePath) -> {
                        if (zipEntry.isDirectory()) return true;
                        if (!callback.test(relativePath)) return false;
                        entries.add(relativePath);

                        if (!files.containsKey(relativePath)) {
                            // If old modpack does not have this entry, add this entry or override the file that user added.
                            return true;
                        } else if (!Files.exists(destFile)) {
                            // If both old and new modpacks have this entry, but the file is deleted by user, leave it missing.
                            return false;
                        } else {
                            // If both old and new modpacks have this entry, and user has modified this file,
                            // we will not replace it since this modified file is what user expects.
                            String fileHash = DigestUtils.digestToString("SHA-1", destFile);
                            String oldHash = files.get(relativePath).getHash();
                            return Objects.equals(oldHash, fileHash);
                        }
                    }).unzip();
        }

        // If old modpack have this entry, and new modpack deleted it. Delete this file.
        for (ModpackConfiguration.FileInformation file : overrides) {
            Path original = dest.resolve(file.getPath());
            if (Files.exists(original) && !entries.contains(file.getPath()))
                Files.deleteIfExists(original);
        }
    }
}
