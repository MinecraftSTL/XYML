// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.game;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameComponentAnalyzer;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// Removes legacy signature metadata from one instance JAR when an old Forge launch requires it.
@NotNullByDefault
public final class GameVerificationFixTask extends Task<Void> {
    private final DefaultDependencyManager dependencyManager;
    private final String gameVersion;
    private final GameInstanceManifest manifest;
    private final List<Task<?>> dependencies = new ArrayList<>();

    /// Creates a verification repair scoped to one instance directory.
    ///
    /// @param dependencyManager repository and dependency services
    /// @param gameVersion canonical game version
    /// @param manifest instance manifest whose JAR may be repaired
    public GameVerificationFixTask(
            DefaultDependencyManager dependencyManager,
            String gameVersion,
            GameInstanceManifest manifest) {
        this.dependencyManager = dependencyManager;
        this.gameVersion = gameVersion;
        this.manifest = manifest;

        setSignificance(TaskSignificance.MODERATE);
        setResources(TaskResource.gameInstance(
                dependencyManager.getGameRepository().getInstanceRoot(manifest.id())));
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public void execute() throws IOException {
        Path jar = dependencyManager.getGameRepository().getInstanceJar(manifest);
        GameComponentAnalyzer analyzer = GameComponentAnalyzer.analyze(manifest, GameVersionNumber.asGameVersion(gameVersion));

        if (Files.exists(jar) && GameVersionNumber.compare(gameVersion, "1.6") < 0 && analyzer.has(GameComponentType.FORGE)) {
            try (FileSystem fs = CompressingUtils.createWritableZipFileSystem(jar, StandardCharsets.UTF_8)) {
                Files.deleteIfExists(fs.getPath("META-INF/MOJANG_C.DSA"));
                Files.deleteIfExists(fs.getPath("META-INF/MOJANG_C.SF"));
            }
        }
    }
    
}
