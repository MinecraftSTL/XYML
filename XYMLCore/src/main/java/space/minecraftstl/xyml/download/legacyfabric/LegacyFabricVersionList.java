/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2022  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download.legacyfabric;

import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.VersionList;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static space.minecraftstl.xyml.util.gson.JsonUtils.listTypeOf;

/// Loads Legacy Fabric game and loader versions from its metadata service.
@NotNullByDefault
public final class LegacyFabricVersionList extends VersionList<LegacyFabricRemoteVersion> {
    private final DownloadProvider downloadProvider;

    public LegacyFabricVersionList(DownloadProvider downloadProvider) {
        this.downloadProvider = downloadProvider;
    }

    @Override
    public boolean hasType() {
        return false;
    }

    @Override
    public Task<?> refreshAsync() {
        return Task.runAsync(() -> {
            List<String> gameVersions = getGameVersions(GAME_META_URL);
            List<String> loaderVersions = getGameVersions(LOADER_META_URL);

            lock.writeLock().lock();

            try {
                for (String metaGameVersion : gameVersions) {
                    String gameVersion = normalizeVersion(metaGameVersion);
                    for (String loaderVersion : loaderVersions) {
                        versions.put(gameVersion, new LegacyFabricRemoteVersion(gameVersion, loaderVersion,
                                Collections.singletonList(getLaunchMetaUrl(metaGameVersion, loaderVersion))));
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    private static final String LOADER_META_URL = "https://meta.legacyfabric.net/v2/versions/loader";
    private static final String GAME_META_URL = "https://meta.legacyfabric.net/v2/versions/game";

    /// Loads version identifiers from a Legacy Fabric metadata endpoint.
    ///
    /// @param metaUrl the metadata endpoint URL
    /// @return the version identifiers returned by the endpoint
    /// @throws IOException if the metadata cannot be downloaded
    private List<String> getGameVersions(String metaUrl) throws IOException {
        String json = NetworkUtils.doGet(downloadProvider.injectURLWithCandidates(metaUrl));
        return JsonUtils.GSON.fromJson(json, listTypeOf(GameVersion.class))
                .stream().map(GameVersion::version).collect(Collectors.toList());
    }

    private static String normalizeVersion(String version) {
        return version.startsWith("2point0_")
                ? "2.0_" + version.substring("2point0_".length())
                : version;
    }

    private static String getLaunchMetaUrl(String gameVersion, String loaderVersion) {
        return String.format("https://meta.legacyfabric.net/v2/versions/loader/%s/%s", gameVersion, loaderVersion);
    }

    /// Describes a game version returned by the Legacy Fabric metadata service.
    ///
    /// @param version the game version identifier
    /// @param maven the optional Maven coordinate
    /// @param stable whether the version is stable
    @JsonSerializable
    @NotNullByDefault
    private record GameVersion(String version, @Nullable String maven, boolean stable) {
    }
}
