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

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

/// Provides format-specific modpack parsing, installation completion, and update tasks.
public interface ModpackProvider {

    String getName();

    /// Creates the optional task that completes metadata and files for an installed modpack instance.
    ///
    /// @param dependencyManager dependency manager bound to the target game repository
    /// @param instanceId target installed instance identifier
    /// @return completion task, or `null` when this format requires no completion phase
    @Nullable Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, String instanceId);

    Task<?> createUpdateTask(DefaultDependencyManager dependencyManager, String name, Path zipFile, Modpack modpack) throws MismatchedModpackTypeException;

    /**
     * @param zipFile the opened modpack zip file.
     * @param file the modpack zip file path.
     * @param encoding encoding of zip file.
     * @throws IOException if the file is not a valid zip file.
     * @throws JsonParseException if the manifest.json is missing or malformed.
     * @return the manifest.
     */
    Modpack readManifest(ZipArchiveReader zipFile, Path file, Charset encoding) throws IOException, JsonParseException;

    default void injectLaunchOptions(String modpackConfigurationJson, LaunchOptions.Builder builder) {
    }
}
