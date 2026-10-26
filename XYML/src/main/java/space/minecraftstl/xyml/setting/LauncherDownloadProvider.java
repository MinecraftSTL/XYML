// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting;

import org.glavo.url.WebURL;
import space.minecraftstl.xyml.download.ComponentRemoteVersionList;
import space.minecraftstl.xyml.download.DownloadCandidate;
import space.minecraftstl.xyml.download.DownloadCandidates;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.cleanroom.CleanroomRemoteVersion;
import space.minecraftstl.xyml.download.fabric.FabricRemoteVersion;
import space.minecraftstl.xyml.download.forge.ForgeRemoteVersion;
import space.minecraftstl.xyml.download.game.GameRemoteVersion;
import space.minecraftstl.xyml.download.neoforge.NeoForgeRemoteVersion;
import space.minecraftstl.xyml.download.optifine.OptiFineRemoteVersion;
import space.minecraftstl.xyml.game.AssetObject;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.RegionUtils;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;

/// Selects official, configured mirror, and service fallback URLs without changing catalog task ownership.
///
/// @author Glavo
@NotNullByDefault
public final class LauncherDownloadProvider extends DownloadProvider {
    private volatile DownloadSource versionListSource = DownloadSource.DEFAULT;
    private volatile DownloadSource fileSource = DownloadSource.DEFAULT;

    private static DownloadCandidates getCandidates(
            DownloadSource source,
            WebURL defaultUrl, WebURL bmclapiUrl
    ) {
        if ("CN".equals(RegionUtils.MACHINE_REGION)) {
            return switch (source) {
                case DEFAULT, OFFICIAL -> DownloadCandidates.of(defaultUrl, bmclapiUrl);
                case MIRROR -> DownloadCandidates.of(bmclapiUrl, defaultUrl);
            };
        } else {
            return switch (source) {
                case DEFAULT, OFFICIAL -> DownloadCandidates.of(defaultUrl);
                case MIRROR -> DownloadCandidates.of(bmclapiUrl, defaultUrl);
            };
        }
    }

    /// Applies matching mirror policies while retaining regional and explicit official-source preferences.
    ///
    /// @param source configured selection policy
    /// @param rules ordered mirror rules
    /// @param urls nonempty source URL list
    /// @return immutable ordered download candidates
    private static DownloadCandidates getCandidates(DownloadSource source, List<MirrorRule> rules, List<String> urls) {
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("urls cannot be empty");
        }

        // true means prefer mirror, false means prefer official, null means do not use mirror
        @Nullable Boolean mirrorFirst;
        if ("CN".equals(RegionUtils.MACHINE_REGION)) {
            mirrorFirst = switch (source) {
                case DEFAULT, MIRROR -> true;
                case OFFICIAL -> false;
            };
        } else {
            mirrorFirst = switch (source) {
                case DEFAULT, OFFICIAL -> null;
                case MIRROR -> true;
            };
        }

        if (mirrorFirst == null) {
            return DownloadCandidates.of(urls.stream().map(DownloadCandidate::of).toArray(DownloadCandidate[]::new));
        }

        var candidates = new ArrayList<DownloadCandidate>(urls.size() * 2);
        for (String url : urls) {
            DownloadCandidate candidate = DownloadCandidate.of(url);
            if (candidate.url() == null) {
                // Invalid URL, just add it to the list and let the download task handle it.
                candidates.add(candidate);
                continue;
            }

            boolean added = false;
            for (MirrorRule rule : rules) {
                @Nullable @Unmodifiable List<DownloadCandidate> matches = rule.getCandidates(candidate, mirrorFirst);
                if (matches != null) {
                    candidates.addAll(matches);
                    added = true;
                    break;
                }
            }
            if (!added)
                candidates.add(candidate);
        }

        return DownloadCandidates.of(List.copyOf(candidates));
    }

    private final String bmclRoot;
    /// Immutable mirror policies evaluated in declaration order.
    private final @Unmodifiable List<MirrorRule> rules;

    /// Creates a launcher provider using the supplied BMCLAPI root and the current regional preference.
    ///
    /// @param bmclRoot root URL used by BMCLAPI mirror policies
    public LauncherDownloadProvider(String bmclRoot) {
        this.bmclRoot = bmclRoot;
        this.rules = List.of(
                new SimpleRule("https://bmclapi2.bangbang93.com", bmclRoot),
                new SimpleRule("https://launchermeta.mojang.com", bmclRoot),
                new SimpleRule("https://piston-meta.mojang.com", bmclRoot),
                new SimpleRule("https://piston-data.mojang.com", bmclRoot),
                new SimpleRule("https://launcher.mojang.com", bmclRoot),
                new SimpleRule("https://libraries.minecraft.net", bmclRoot + "/libraries"),
                new SimpleRule("http://files.minecraftforge.net/maven", bmclRoot + "/maven"),
                new SimpleRule("https://files.minecraftforge.net/maven", bmclRoot + "/maven"),
                new SimpleRule("https://maven.minecraftforge.net", bmclRoot + "/maven"),
                (candidate, mirrorFirst) -> neoForgeCandidates(candidate, mirrorFirst, bmclRoot),
                new SimpleRule("http://dl.liteloader.com/versions", bmclRoot + "/maven"),
                new SimpleRule("https://dl.liteloader.com/versions", bmclRoot + "/maven"),
                new SimpleRule("https://meta.fabricmc.net", bmclRoot + "/fabric-meta"),
                new SimpleRule("https://maven.fabricmc.net", bmclRoot + "/maven"),
                new SimpleRule("https://authlib-injector.yushi.moe", bmclRoot + "/mirrors/authlib-injector"),
                new SimpleRule("https://repo1.maven.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
                new SimpleRule("https://repo.maven.apache.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
                new SimpleRule("https://hmcl.glavo.site/metadata/cleanroom", "https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/cleanroom"),
                new SimpleRule("https://hmcl.glavo.site/metadata/fmllibs", "https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/fmllibs"),
                new SimpleRule("https://zkitefly.github.io/unlisted-versions-of-minecraft", "https://alist.8mi.tech/d/mirror/unlisted-versions-of-minecraft/Auto"),

                // https://github.com/mcmod-info-mirror/mcim-rust-api
                new SimpleRule("https://api.modrinth.com", null, "https://mod.mcimirror.top/modrinth"),
                new SimpleRule("https://cdn.modrinth.com", null, "https://mod.mcimirror.top"),
                new SimpleRule("https://api.curseforge.com", null, "https://mod.mcimirror.top/curseforge"),
                new SimpleRule("https://edge.forgecdn.net", null, "https://mod.mcimirror.top")
        );
    }

    @Override
    protected Task<? extends ComponentRemoteVersionList<?>> fetchVersionsAsync(GameComponentType type, @Nullable GameVersionNumber gameVersion) {
        assert (gameVersion == null) == (type == GameComponentType.GAME);
        switch (type) {
            case FORGE -> {
                Task<ComponentRemoteVersionList<ForgeRemoteVersion>> fetchOfficial = ForgeRemoteVersion.fetchAsync(this, gameVersion);

                DownloadSource source = versionListSource;
                if (!"CN".equals(RegionUtils.MACHINE_REGION) && (source == DownloadSource.DEFAULT || source == DownloadSource.OFFICIAL)) {
                    return fetchOfficial;
                }

                Task<ComponentRemoteVersionList<ForgeRemoteVersion>> fetchBMCL = ForgeRemoteVersion.fetchBMCLAsync(this, bmclRoot, gameVersion);
                if (source == DownloadSource.MIRROR) {
                    return new FallbackTask<>(fetchBMCL, fetchOfficial);
                } else {
                    return new FallbackTask<>(fetchOfficial, fetchBMCL);
                }
            }
            case NEO_FORGE -> {
                Task<ComponentRemoteVersionList<NeoForgeRemoteVersion>> fetchOfficial = NeoForgeRemoteVersion.fetchAsync(this, gameVersion);

                DownloadSource source = versionListSource;
                if (!"CN".equals(RegionUtils.MACHINE_REGION) && (source == DownloadSource.DEFAULT || source == DownloadSource.OFFICIAL)) {
                    return fetchOfficial;
                }
                Task<ComponentRemoteVersionList<NeoForgeRemoteVersion>> fetchBMCL = NeoForgeRemoteVersion.fetchBMCLAsync(bmclRoot, gameVersion);
                if (source == DownloadSource.MIRROR || source == DownloadSource.DEFAULT) {
                    return new FallbackTask<>(fetchBMCL, fetchOfficial);
                } else {
                    return new FallbackTask<>(fetchOfficial, fetchBMCL);
                }
            }
            case OPTIFINE -> {
                return OptiFineRemoteVersion.fetchBMCLAsync(this, bmclRoot, gameVersion);
            }
        }

        return super.fetchVersionsAsync(type, gameVersion);
    }

    public void setFileSource(DownloadSource fileSource) {
        this.fileSource = Objects.requireNonNull(fileSource);
    }

    public void setVersionListSource(DownloadSource versionListSource) {
        this.versionListSource = Objects.requireNonNull(versionListSource);
    }

    @Override
    public DownloadCandidates getGameVersionListCandidates() {
        return getCandidates(
                versionListSource,
                GameRemoteVersion.VERSION_MANIFEST_URL,
                WebURL.parse(bmclRoot + "/mc/game/version_manifest.json")
        );
    }

    @Override
    public FabricLikeVersionListCandidates getFabricVersionListCandidates() {
        DownloadSource source = versionListSource;
        return new FabricLikeVersionListCandidates(
                getCandidates(source, FabricRemoteVersion.LOADER_META_URL, WebURL.parse(bmclRoot + "/fabric-meta/v2/versions/loader")),
                getCandidates(source, FabricRemoteVersion.GAME_META_URL, WebURL.parse(bmclRoot + "/fabric-meta/v2/versions/game"))
        );
    }

    @Override
    public DownloadCandidates getCleanroomVersionListCandidates() {
        return "CN".equals(RegionUtils.MACHINE_REGION)
                ? DownloadCandidates.of(CleanroomRemoteVersion.LOADER_LIST_URL, WebURL.parse("https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/cleanroom/index.json"))
                : DownloadCandidates.of(CleanroomRemoteVersion.LOADER_LIST_URL);
    }

    @Override
    public DownloadCandidates getDownloadCandidates(List<String> urls) {
        return getCandidates(fileSource, rules, urls);
    }

    @Override
    public DownloadCandidates getVersionListCandidates(List<String> urls) {
        return getCandidates(versionListSource, rules, urls);
    }

    @Override
    public DownloadCandidates getAssetObjectCandidates(AssetObject assetObject) {
        return getCandidates(
                fileSource,
                WebURL.parse("https://resources.download.minecraft.net/" + assetObject.getLocation()),
                WebURL.parse(bmclRoot + "/assets/" + assetObject.getLocation())
        );
    }

    /// Coordinates two stopped catalog queries, using the fallback only when the primary query fails.
    @NotNullByDefault
    private static final class FallbackTask<T> extends Task<T> {

        /// Primary query retaining its own download and parsing resource declarations.
        private final Task<? extends T> task;
        /// Secondary query executed after primary failure.
        private final Task<? extends T> fallback;

        /// Immutable successor snapshot, empty until primary failure requires the fallback.
        private @Unmodifiable List<Task<?>> dependencies = List.of();

        /// Creates a coordination-only node without replacing either concrete query's resource declarations.
        ///
        /// @param task primary stopped query
        /// @param fallback secondary stopped query
        public FallbackTask(Task<? extends T> task, Task<? extends T> fallback) {
            this.task = task;
            this.fallback = fallback;
            asOrchestration();
        }

        /// Returns the immutable primary-query prerequisite.
        @Override
        public @Unmodifiable Collection<? extends Task<?>> getDependents() {
            return List.of(task);
        }

        /// Allows the coordinator to select the fallback after prerequisite failure.
        @Override
        public boolean isRelyingOnDependents() {
            return false;
        }

        /// Publishes the primary result or schedules the secondary query after primary failure.
        @Override
        public void execute() throws Exception {
            if (isDependentsSucceeded()) {
                setResult(task.getResult());
            } else {
                dependencies = List.of(fallback);
                fallback.storeTo(this::setResult);
            }
        }

        /// Returns the immutable fallback-query successor snapshot.
        @Override
        public @Unmodifiable List<Task<?>> getDependencies() {
            return dependencies;
        }
    }

    /// Produces ordered NeoForge fallbacks without changing the configured regional preference.
    ///
    /// @param source original metadata or artifact URL
    /// @param mirrorFirst whether BMCLAPI precedes the official release URL
    /// @param bmclRoot configured BMCLAPI root
    /// @return immutable candidates, or null when this is not an official NeoForge Maven URL
    static @Nullable @Unmodifiable List<DownloadCandidate> neoForgeCandidates(
            DownloadCandidate source, boolean mirrorFirst, String bmclRoot) {
        String maven = "https://maven.neoforged.net/";
        String releases = maven + "releases/";
        if (source.url() == null || !source.url().href().startsWith(maven))
            return null;
        String url = source.url().href();
        List<DownloadCandidate> candidates = new ArrayList<>();
        if (url.startsWith(releases)) {
            DownloadCandidate mirror = DownloadCandidate.of(bmclRoot + "/maven/" + url.substring(releases.length()));
            if (mirrorFirst) {
                candidates.add(mirror);
                candidates.add(source);
            } else {
                candidates.add(source);
                candidates.add(mirror);
            }
        } else {
            candidates.add(source);
        }
        candidates.add(DownloadCandidate.of("https://neoforged.forgecdn.net/" + url.substring(maven.length())));
        return List.copyOf(candidates);
    }

    /// Matches one service and returns its ordered immutable download candidates.
    @NotNullByDefault
    @FunctionalInterface
    private interface MirrorRule {
        /// Returns candidates, or null when the source URL does not match this policy.
        @Nullable @Unmodifiable List<DownloadCandidate> getCandidates(DownloadCandidate source, boolean mirrorFirst);
    }

    /// Rewrites a URL prefix while preserving source-first behavior for fallback-only services.
    ///
    /// @param source matched source URL prefix
    /// @param first preferred mirror prefix, or null for a fallback-only service
    /// @param fallback trailing fallback prefix, or null when no additional fallback exists
    @NotNullByDefault
    private record SimpleRule(String source, @Nullable String first, @Nullable String fallback) implements MirrorRule {
        /// Creates a policy with one preferred mirror and no additional fallback.
        ///
        /// @param source matched source URL prefix
        /// @param target preferred mirror prefix
        public SimpleRule(String source, String target) {
            this(source, target, null);
        }

        /// Returns a defensive immutable candidate snapshot, or null when the source prefix does not match.
        @Override
        public @Nullable @Unmodifiable List<DownloadCandidate> getCandidates(DownloadCandidate source, boolean mirrorFirst) {
            if (source.url() == null || !source.url().href().startsWith(this.source))
                return null;
            String suffix = source.url().href().substring(this.source.length());
            boolean sourceFirst = !mirrorFirst || first == null;
            List<DownloadCandidate> candidates = new ArrayList<>();
            if (sourceFirst)
                candidates.add(source);
            if (first != null)
                candidates.add(DownloadCandidate.of(first + suffix));
            if (!sourceFirst)
                candidates.add(source);
            if (fallback != null)
                candidates.add(DownloadCandidate.of(fallback + suffix));
            return List.copyOf(candidates);
        }
    }
}
