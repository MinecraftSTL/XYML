// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.cleanroom;

import com.google.gson.annotations.SerializedName;
import org.glavo.url.WebURL;
import space.minecraftstl.xyml.download.*;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

/// Describes a downloadable Cleanroom version and its catalog queries.
@NotNullByDefault
public final class CleanroomRemoteVersion extends ComponentRemoteVersion {
    private static final GameVersionNumber GAME_VERSION_1_12_2 = GameVersionNumber.asGameVersion("1.12.2");

    public static final WebURL LOADER_LIST_URL = WebURL.parse("https://hmcl.glavo.site/metadata/cleanroom/index.json");

    /// Fetches Cleanroom metadata for Minecraft 1.12.2, returning an empty catalog for other versions.
    ///
    /// @param downloadProvider official metadata endpoints
    /// @param gameVersion target game version
    /// @return stopped query producing an immutable Cleanroom catalog
    public static Task<@Unmodifiable ComponentRemoteVersionList<CleanroomRemoteVersion>> fetchAsync(
            DownloadProvider downloadProvider, GameVersionNumber gameVersion) {
        if (!gameVersion.equals(GAME_VERSION_1_12_2)) {
            return Task.completed(ComponentRemoteVersionList.of(GameComponentType.CLEANROOM));
        }

        /// Parser-owned Cleanroom release name and creation timestamp.
        @JsonSerializable
        @NotNullByDefault
        record ReleaseResult(String name, @SerializedName("created_at") String createdAt) {
        }

        return new GetTask(downloadProvider.getCleanroomVersionListCandidates()).thenApplyAsync(result -> {
            var results = JsonUtils.fromNonNullJson(result, JsonUtils.listTypeOf(ReleaseResult.class));

            var versions = new TreeSet<CleanroomRemoteVersion>();
            for (ReleaseResult version : results) {
                versions.add(new CleanroomRemoteVersion(
                        GAME_VERSION_1_12_2, version.name, Instant.parse(version.createdAt),
                        List.of("https://hmcl.glavo.site/metadata/cleanroom/files/cleanroom-%s-installer.jar".formatted(version.name))
                ));
            }
            return ComponentRemoteVersionList.of(GameComponentType.CLEANROOM, versions);
        }).asOrchestration();
    }

    public CleanroomRemoteVersion(GameVersionNumber gameVersion, String selfVersion, Instant releaseDate, List<String> url) {
        super(GameComponentType.CLEANROOM, gameVersion, selfVersion, releaseDate, Type.UNCATEGORIZED, url);
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest baseManifest, Path modsDirectory) {
        return new CleanroomInstallTask(dependencyManager, baseManifest, this);
    }
}
