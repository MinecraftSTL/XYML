// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.neoforge;

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
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import static space.minecraftstl.xyml.util.gson.JsonUtils.listTypeOf;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Describes a downloadable NeoForge version and its catalog queries.
@NotNullByDefault
public final class NeoForgeRemoteVersion extends ComponentRemoteVersion {

    private static final GameVersionNumber GAME_VERSION_1_20_1 = GameVersionNumber.asGameVersion("1.20.1");

    public static final WebURL OLD_URL = WebURL.parse("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/forge");
    public static final WebURL META_URL = WebURL.parse("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge");

    /// Secondary endpoint for legacy NeoForge metadata when the primary Maven API is unavailable.
    public static final WebURL MIRROR_OLD_URL = WebURL.parse("https://neoforged.forgecdn.net/api/maven/versions/releases/net/neoforged/forge");

    /// Secondary endpoint for current NeoForge metadata when the primary Maven API is unavailable.
    public static final WebURL MIRROR_META_URL = WebURL.parse("https://neoforged.forgecdn.net/api/maven/versions/releases/net/neoforged/neoforge");

    private static Type getType(String version) {
        return version.contains("beta") || version.contains("alpha") ? Type.SNAPSHOT : Type.RELEASE;
    }

    private static String normalize(String version) {
        if (version.startsWith("1.20.1-")) {
            if (version.startsWith("forge-", "1.20.1-".length())) {
                return version.substring("1.20.1-forge-".length());
            } else {
                return version.substring("1.20.1-".length());
            }
        } else {
            return version;
        }
    }

    /// Fetches and parses old or modern NeoForge metadata without filesystem writes in the parsing phase.
    ///
    /// @param downloadProvider official metadata endpoints
    /// @param gameVersion target game version
    /// @return stopped query producing an immutable NeoForge catalog
    public static Task<@Unmodifiable ComponentRemoteVersionList<NeoForgeRemoteVersion>> fetchAsync(
            DownloadProvider downloadProvider, GameVersionNumber gameVersion) {
        /// Parser-owned version metadata consumed within this query.
        @JsonSerializable
        @NotNullByDefault
        record OfficialAPIResult(boolean isSnapshot, List<String> versions) {
        }

        boolean isOld = gameVersion.equals(GAME_VERSION_1_20_1);
        return new GetTask(isOld ? downloadProvider.getNeoForgeOldVersionListCandidates() : downloadProvider.getNeoForgeVersionListCandidates())
                .thenApplyAsync(result -> {
                    OfficialAPIResult apiResult = JsonUtils.fromNonNullJson(result, OfficialAPIResult.class);

                    TreeSet<NeoForgeRemoteVersion> versions = new TreeSet<>();
                    if (isOld) {
                        for (String version : apiResult.versions) {
                            versions.add(new NeoForgeRemoteVersion(
                                    GAME_VERSION_1_20_1,
                                    NeoForgeRemoteVersion.normalize(version),
                                    version,
                                    List.of(
                                            "https://maven.neoforged.net/releases/net/neoforged/forge/" + version + "/forge-" + version + "-installer.jar"
                                    )));
                        }
                    } else {
                        for (String version : apiResult.versions) {
                            GameVersionNumber mcVersion;

                            try {
                                int si1 = version.indexOf('.');
                                int si2 = version.indexOf('.', si1 + 1);
                                if (si1 < 0 || si2 < 0) {
                                    LOG.warning("Unsupported NeoForge version: " + version);
                                    continue;
                                }

                                int majorVersion = Integer.parseInt(version.substring(0, si1));
                                if (majorVersion == 0) { // Snapshot version.
                                    mcVersion = GameVersionNumber.asGameVersion(version.substring(si1 + 1, si2));
                                } else {
                                    if (majorVersion >= 26) {
                                        int si3 = version.indexOf('.', si2 + 1);

                                        if (si3 < 0) {
                                            LOG.warning("Unsupported NeoForge version: " + version);
                                            continue;
                                        }

                                        String ver = Integer.parseInt(version.substring(si2 + 1, si3)) == 0
                                                ? version.substring(0, si2)
                                                : version.substring(0, si3);

                                        int separator = version.indexOf('+');
                                        if (separator < 0)
                                            mcVersion = GameVersionNumber.asGameVersion(ver);
                                        else
                                            mcVersion = GameVersionNumber.asGameVersion(ver + "-" + version.substring(separator + 1));
                                    } else {
                                        String ver = Integer.parseInt(version.substring(si1 + 1, si2)) == 0
                                                ? version.substring(0, si1)
                                                : version.substring(0, si2);
                                        mcVersion = GameVersionNumber.asGameVersion("1." + ver);
                                    }
                                }
                            } catch (RuntimeException e) {
                                LOG.warning("Cannot parse NeoForge version %s for cracking its mc version.".formatted(version), e);
                                continue;
                            }


                            if (gameVersion.equals(mcVersion)) {
                                versions.add(new NeoForgeRemoteVersion(
                                        mcVersion, NeoForgeRemoteVersion.normalize(version), version,
                                        List.of(
                                                "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar"
                                        )));
                            }
                        }
                    }

                    return ComponentRemoteVersionList.of(GameComponentType.NEO_FORGE, versions);
                }).asOrchestration();

    }

    /// Fetches and parses mirrored NeoForge metadata without filesystem writes.
    ///
    /// @param bmclRoot mirror origin
    /// @param gameVersion target game version
    /// @return stopped query producing an immutable catalog
    public static Task<@Unmodifiable ComponentRemoteVersionList<NeoForgeRemoteVersion>> fetchBMCLAsync(String bmclRoot, GameVersionNumber gameVersion) {
        /// Parser-owned version metadata consumed within this query.
        @JsonSerializable
        @NotNullByDefault
        record NeoForgeBMCLVersion(String rawVersion,
                                   String version,
                                   @SerializedName("mcversion") String mcVersion) {
            /// Validates the required mirror identifiers before constructing a remote version.
            public NeoForgeBMCLVersion {
                Objects.requireNonNull(rawVersion, "rawVersion");
                Objects.requireNonNull(version, "version");
                Objects.requireNonNull(mcVersion, "mcversion");
            }
        }

        return new GetTask(DownloadCandidates.of(bmclRoot + "/neoforge/list/" + gameVersion)).thenGetJsonAsync(listTypeOf(NeoForgeBMCLVersion.class))
                .thenApplyAsync(neoForgeVersions -> {
                    var versions = new TreeSet<NeoForgeRemoteVersion>();
                    for (NeoForgeBMCLVersion neoForgeVersion : neoForgeVersions) {
                        versions.add(new NeoForgeRemoteVersion(
                                GameVersionNumber.asGameVersion(neoForgeVersion.mcVersion),
                                NeoForgeRemoteVersion.normalize(neoForgeVersion.version),
                                neoForgeVersion.version,
                                List.of(bmclRoot + "/neoforge/version/" + neoForgeVersion.version + "/download/installer.jar")));
                    }
                    return ComponentRemoteVersionList.of(GameComponentType.NEO_FORGE, versions);
                }).asOrchestration();
    }

    private final String fullVersion;

    public NeoForgeRemoteVersion(GameVersionNumber gameVersion, String selfVersion, String fullVersion, List<String> urls) {
        super(GameComponentType.NEO_FORGE, gameVersion, selfVersion, null, getType(selfVersion), urls);
        this.fullVersion = fullVersion;
    }

    @Override
    public String getFullVersion() {
        return fullVersion;
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest baseManifest, Path modsDirectory) {
        return new NeoForgeInstallTask(dependencyManager, baseManifest, this);
    }

}
