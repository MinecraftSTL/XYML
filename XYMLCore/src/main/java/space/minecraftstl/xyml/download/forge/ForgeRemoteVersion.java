// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.forge;

import org.glavo.url.WebURL;
import space.minecraftstl.xyml.download.*;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Pair.pair;
import static space.minecraftstl.xyml.util.gson.JsonUtils.listTypeOf;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Describes a downloadable Forge version and its catalog queries.
@NotNullByDefault
public final class ForgeRemoteVersion extends ComponentRemoteVersion {

    public static final WebURL FORGE_LIST = WebURL.parse("https://hmcl.glavo.site/metadata/forge/");

    private static String toLookupVersion(String gameVersion) {
        return "1.7.10-pre4".equals(gameVersion) ? "1.7.10_pre4" : gameVersion;
    }

    private static String fromLookupVersion(String lookupVersion) {
        return "1.7.10_pre4".equals(lookupVersion) ? "1.7.10-pre4" : lookupVersion;
    }

    private static String toLookupBranch(String gameVersion, @Nullable String branch) {
        if ("1.7.10-pre4".equals(gameVersion)) {
            return "prerelease";
        }
        return Objects.requireNonNullElse(branch, "");
    }

    /// Fetches and parses official Forge metadata without filesystem writes in the parsing phase.
    ///
    /// @param downloadProvider official metadata endpoints
    /// @param gameVersion target game version
    /// @return stopped query producing an immutable Forge catalog
    public static Task<@Unmodifiable ComponentRemoteVersionList<ForgeRemoteVersion>> fetchAsync(DownloadProvider downloadProvider, GameVersionNumber gameVersion) {
        return new GetTask(downloadProvider.getForgeVersionListCandidates()).thenApplyAsync(result -> {
            ForgeVersionRoot root = JsonUtils.GSON.fromJson(result, ForgeVersionRoot.class);

            TreeSet<ForgeRemoteVersion> versions = new TreeSet<>();

            for (Map.Entry<String, int[]> entry : root.mcversion().entrySet()) {
                if (gameVersion.equals(GameVersionNumber.asGameVersion(fromLookupVersion(entry.getKey())))) {
                    for (int v : entry.getValue()) {
                        ForgeVersion version = root.number().get(v);
                        if (version == null)
                            continue;
                        @Nullable String jar = null;
                        for (String[] file : version.getFiles())
                            if (file.length > 1 && "installer".equals(file[1])) {
                                String classifier = version.getGameVersion() + "-" + version.getVersion()
                                        + (StringUtils.isNotBlank(version.getBranch()) ? "-" + version.getBranch() : "");
                                String fileName = root.artifact() + "-" + classifier + "-" + file[1] + "." + file[0];
                                jar = root.webpath() + classifier + "/" + fileName;
                            }

                        if (jar == null)
                            continue;

                        versions.add(new ForgeRemoteVersion(
                                gameVersion,
                                version.getVersion(),
                                version.getModified() > 0 ? Instant.ofEpochSecond(version.getModified()) : null,
                                List.of(jar)
                        ));
                    }
                    break;
                }
            }

            return ComponentRemoteVersionList.of(GameComponentType.FORGE, versions);
        }).asOrchestration();
    }

    /// Fetches and parses mirrored Forge metadata without filesystem writes.
    ///
    /// @param downloadProvider caller's download provider
    /// @param bmclRoot mirror origin
    /// @param gameVersion target game version
    /// @return stopped query producing an immutable catalog
    public static Task<@Unmodifiable ComponentRemoteVersionList<ForgeRemoteVersion>> fetchBMCLAsync(
            DownloadProvider downloadProvider, String bmclRoot, GameVersionNumber gameVersion) {
        String lookupVersion = toLookupVersion(gameVersion.toString());

        return new GetTask(DownloadCandidates.of(bmclRoot + "/forge/minecraft/" + lookupVersion)).thenApplyAsync(result -> {
            List<@Nullable ForgeBMCLVersion> forgeVersions = JsonUtils.fromNonNullJson(result, listTypeOf(ForgeBMCLVersion.class));

            TreeSet<ForgeRemoteVersion> versions = new TreeSet<>();
            for (ForgeBMCLVersion version : forgeVersions) {
                //noinspection ConstantValue
                if (version == null)
                    continue;

                List<String> urls = new ArrayList<>();
                for (ForgeBMCLVersion.File file : version.files())
                    if ("installer".equals(file.category()) && "jar".equals(file.format())) {
                        String branch = toLookupBranch(gameVersion.toString(), version.branch());

                        String classifier = lookupVersion + "-" + version.version() + (branch.isEmpty() ? "" : '-' + branch);
                        String fileName1 = "forge-" + classifier + "-" + file.category() + "." + file.format();
                        String fileName2 = "forge-" + classifier + "-" + lookupVersion + "-" + file.category() + "." + file.format();
                        urls.add("https://files.minecraftforge.net/maven/net/minecraftforge/forge/" + classifier + "/" + fileName1);
                        urls.add("https://files.minecraftforge.net/maven/net/minecraftforge/forge/" + classifier + "-" + lookupVersion + "/" + fileName2);
                        urls.add(NetworkUtils.withQuery(bmclRoot + "/forge/download", mapOf(
                                pair("mcversion", version.mcversion()),
                                pair("version", version.version()),
                                pair("branch", branch),
                                pair("category", file.category()),
                                pair("format", file.format())
                        )));
                    }

                if (urls.isEmpty())
                    continue;

                @Nullable Instant releaseDate = null;
                if (version.modified() != null) {
                    try {
                        releaseDate = Instant.parse(version.modified());
                    } catch (DateTimeParseException e) {
                        LOG.warning("Failed to parse instant " + version.modified(), e);
                    }
                }

                versions.add(new ForgeRemoteVersion(gameVersion, version.version(), releaseDate, urls));
            }

            return ComponentRemoteVersionList.of(GameComponentType.FORGE, versions);
        }).asOrchestration();
    }

    /**
     * Constructor.
     *
     * @param gameVersion the Minecraft version that this remote version suits.
     * @param selfVersion the version string of the remote version.
     * @param url         the installer or universal jar original URL.
     */
    public ForgeRemoteVersion(GameVersionNumber gameVersion, String selfVersion, @Nullable Instant releaseDate, List<String> url) {
        super(GameComponentType.FORGE, gameVersion, selfVersion, releaseDate, Type.UNCATEGORIZED, url);
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest baseManifest, Path modsDirectory) {
        return new ForgeInstallTask(dependencyManager, baseManifest, this);
    }
}
