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
package org.glavo.gradle;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.file.PathUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.tukaani.xz.XZInputStream;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public abstract class UncompressResources extends DefaultTask {

    @InputDirectory
    public abstract DirectoryProperty getInputDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws IOException {
        Path inputDir = getInputDir().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path outputDir = getOutputDir().get().getAsFile().toPath().toAbsolutePath().normalize();

        PathUtils.deleteDirectory(outputDir);

        Files.walkFileTree(inputDir, new SimpleFileVisitor<Path>() {
            private Path targetDir = outputDir;

            @Override
            public @NonNull FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(inputDir)) {
                    targetDir = targetDir.resolve(dir.getFileName());
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, @Nullable IOException exc) throws IOException {
                if (!dir.equals(inputDir)) {
                    targetDir = targetDir.getParent();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".xz")) {
                    Path targetFile = targetDir.resolve(fileName.substring(0, fileName.length() - ".xz".length()));
                    try (var input = new XZInputStream(Files.newInputStream(file))) {
                        Files.copy(input, targetFile);
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }
}
