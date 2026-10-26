// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.optifine;

import space.minecraftstl.xyml.download.*;
import space.minecraftstl.xyml.download.game.GameDownloadTask;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

import static space.minecraftstl.xyml.util.gson.JsonUtils.listTypeOf;

/// Describes a downloadable OptiFine version and its catalog queries.
@NotNullByDefault
public final class OptiFineRemoteVersion extends ComponentRemoteVersion {

    private static String toLookupVersion(String version) {
        return switch (version) {
            case "1.8" -> "1.8.0";
            case "1.9" -> "1.9.0";
            default -> version;
        };
    }

    private static String fromLookupVersion(String version) {
        return switch (version) {
            case "1.8.0" -> "1.8";
            case "1.9.0" -> "1.9";
            default -> version;
        };
    }

    /// Fetches and parses mirrored OptiFine metadata without filesystem writes in the parsing phase.
    ///
    /// @param downloadProvider caller's download provider
    /// @param bmclRoot mirror origin
    /// @param gameVersion target game version
    /// @return stopped query producing an immutable OptiFine catalog
    public static Task<@Unmodifiable ComponentRemoteVersionList<OptiFineRemoteVersion>> fetchBMCLAsync(DownloadProvider downloadProvider, String bmclRoot, GameVersionNumber gameVersion) {
        /// Parser-owned mirrored OptiFine version metadata.
        @JsonSerializable
        @NotNullByDefault
        record OptiFineVersion(String dl, String ver,
                               String date, String type,
                               @Nullable String patch, String mirror,
                               String mcversion) {
        }

        return new GetTask(DownloadCandidates.of(bmclRoot + "/optifine/" + toLookupVersion(gameVersion.toNormalizedString()))).thenApplyAsync(result -> {
            var root = JsonUtils.fromNonNullJson(result, listTypeOf(OptiFineVersion.class));

            var versions = new TreeSet<OptiFineRemoteVersion>();
            Set<String> duplicates = new HashSet<>();
            for (OptiFineVersion element : root) {
                String version = element.type() + "_" + element.patch();
                String mirror = bmclRoot + "/optifine/" + toLookupVersion(element.mcversion()) + "/" + element.type() + "/" + element.patch();
                if (!duplicates.add(mirror))
                    continue;

                boolean isPre = element.patch() != null && (element.patch().startsWith("pre") || element.patch().startsWith("alpha"));

                if (StringUtils.isBlank(element.mcversion()))
                    continue;

                versions.add(new OptiFineRemoteVersion(gameVersion, version, List.of(mirror), isPre));
            }

            return ComponentRemoteVersionList.of(GameComponentType.OPTIFINE, versions);
        }).asOrchestration();

    }

    private final String fullVersion;

    public OptiFineRemoteVersion(GameVersionNumber gameVersion, String selfVersion, List<String> urls,
                                 boolean snapshot) {
        super(GameComponentType.OPTIFINE, gameVersion, selfVersion, null, snapshot ? Type.SNAPSHOT : Type.RELEASE, urls);
        this.fullVersion = getGameVersion() + "_" + getSelfVersion();
    }

    @Override
    public String getFullVersion() {
        return fullVersion;
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest
            baseManifest, Path modsDirectory) {
        return new GameDownloadTask(dependencyManager, getGameVersion().toString(), baseManifest)
                .thenComposeAsync(minecraftJar -> new OptiFineInstallTask(
                        dependencyManager,
                        baseManifest,
                        this,
                        minecraftJar));
    }
}
