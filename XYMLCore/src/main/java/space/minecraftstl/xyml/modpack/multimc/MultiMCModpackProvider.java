// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.multimc;

import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.modpack.MismatchedModpackTypeException;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.modpack.ModpackUpdateTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

public final class MultiMCModpackProvider implements ModpackProvider {
    public static final MultiMCModpackProvider INSTANCE = new MultiMCModpackProvider();

    @Override
    public String getName() {
        return "MultiMC";
    }

    @Override
    public Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, GameInstanceID instanceId) {
        return null;
    }

    @Override
    public Task<?> createUpdateTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceID instanceId,
            Path zipFile,
            Modpack modpack,
            @Nullable Set<String> excludedFiles) throws MismatchedModpackTypeException {
        if (!(modpack.getManifest() instanceof MultiMCInstanceConfiguration multiMCInstanceConfiguration))
            throw new MismatchedModpackTypeException(getName(), modpack.getManifest().getProvider().getName());

        return new ModpackUpdateTask(dependencyManager.getGameRepository(), instanceId, new MultiMCModpackInstallTask(dependencyManager, zipFile, modpack, multiMCInstanceConfiguration, instanceId));
    }

    private static String getRootEntryName(ZipArchiveReader file) throws IOException {
        final String instanceFileName = "instance.cfg";

        if (file.getEntry(instanceFileName) != null) return "";

        for (ZipArchiveEntry entry : file.getEntries()) {
            String entryName = entry.getName();

            int idx = entryName.indexOf('/');
            if (idx >= 0
                    && entryName.length() == idx + instanceFileName.length() + 1
                    && entryName.startsWith(instanceFileName, idx + 1))
                return entryName.substring(0, idx + 1);
        }

        throw new IOException("Not a valid MultiMC modpack");
    }

    @Override
    public Modpack readManifest(ZipArchiveReader modpackFile, Path modpackPath, Charset encoding) throws IOException {
        String rootEntryName = getRootEntryName(modpackFile);
        MultiMCManifest manifest = MultiMCManifest.readMultiMCModpackManifest(modpackFile, rootEntryName);

        String name = rootEntryName.isEmpty() ? FileUtils.getNameWithoutExtension(modpackPath) : rootEntryName.substring(0, rootEntryName.length() - 1);
        ZipArchiveEntry instanceEntry = modpackFile.getEntry(rootEntryName + "instance.cfg");

        if (instanceEntry == null)
            throw new IOException("`instance.cfg` not found, " + modpackFile + " is not a valid MultiMC modpack.");
        try (InputStream instanceStream = modpackFile.getInputStream(instanceEntry)) {
            MultiMCInstanceConfiguration cfg = new MultiMCInstanceConfiguration(name, instanceStream, manifest);
            return new Modpack(cfg.getName(), "", "", cfg.getGameVersion(), cfg.getNotes(), encoding, cfg) {
                @Override
                public Task<?> getInstallTask(
                        DefaultDependencyManager dependencyManager,
                        Path zipFile,
                        GameInstanceID instanceId,
                        String iconUrl,
                        @Nullable Set<String> excludedFiles) {
                    return new MultiMCModpackInstallTask(dependencyManager, zipFile, this, cfg, instanceId);
                }
            };
        }
    }

}
