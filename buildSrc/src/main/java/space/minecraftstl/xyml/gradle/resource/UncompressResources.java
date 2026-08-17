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
package space.minecraftstl.xyml.gradle.resource;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNullByDefault;
import org.tukaani.xz.XZInputStream;

import javax.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/// Expands every XZ-compressed resource below an input directory into a reproducible build directory.
@NotNullByDefault
@DisableCachingByDefault(because = "The output is small and already tracked by Gradle input/output snapshots")
public abstract class UncompressResources extends DefaultTask {
    /// Returns the directory containing resources whose filenames end in `.xz`.
    ///
    /// @return compressed resource directory
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getInputDirectory();

    /// Returns the generated resource directory.
    ///
    /// @return uncompressed resource directory
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /// Returns Gradle's scoped filesystem operations service.
    ///
    /// @return filesystem operations service
    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    /// Replaces the output directory and expands each input while retaining its relative path.
    ///
    /// @throws IOException when an input cannot be read or an output cannot be created
    @TaskAction
    public final void uncompress() throws IOException {
        Path inputDirectory = getInputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        getFileSystemOperations().delete(spec -> spec.delete(outputDirectory));
        Files.createDirectories(outputDirectory);

        try (Stream<Path> paths = Files.walk(inputDirectory)) {
            for (Path inputFile : paths.filter(Files::isRegularFile).toList()) {
                String fileName = inputFile.getFileName().toString();
                if (!fileName.endsWith(".xz")) {
                    continue;
                }

                Path relativePath = inputDirectory.relativize(inputFile);
                Path parent = relativePath.getParent();
                String outputFileName = fileName.substring(0, fileName.length() - ".xz".length());
                Path outputFile = parent == null
                        ? outputDirectory.resolve(outputFileName)
                        : outputDirectory.resolve(parent).resolve(outputFileName);
                Files.createDirectories(outputFile.getParent());
                try (InputStream input = new XZInputStream(Files.newInputStream(inputFile))) {
                    Files.copy(input, outputFile);
                }
            }
        }
    }
}
