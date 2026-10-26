// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack;

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveReader;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.task.Task;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Set;

/// Provides format-specific modpack parsing, installation completion, and update tasks.
public interface ModpackProvider {

    String getName();

    Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, GameInstanceID instanceId);

    Task<?> createUpdateTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceID instanceId,
            Path zipFile,
            Modpack modpack,
            @Nullable Set<String> excludedFiles) throws MismatchedModpackTypeException;

    /// Creates an update task with all optional files enabled.
    ///
    /// @param dependencyManager dependency manager for the target repository
    /// @param instanceId target instance identifier
    /// @param zipFile modpack archive
    /// @param modpack parsed modpack
    /// @return the update task
    default Task<?> createUpdateTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceID instanceId,
            Path zipFile,
            Modpack modpack) throws MismatchedModpackTypeException {
        return createUpdateTask(dependencyManager, instanceId, zipFile, modpack, null);
    }

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

    /// Enriches optional file entries with remote metadata when supported.
    ///
    /// @param downloadProvider provider used for remote metadata queries
    /// @param manifest parsed manifest
    /// @return the enriched manifest, or the original manifest when no enrichment is available
    default ModpackManifest loadFiles(DownloadProvider downloadProvider, ModpackManifest manifest) {
        return manifest;
    }
}
