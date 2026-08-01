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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/// Builds supported Chunk Base links without depending on JavaFX, Swing, or desktop integration.
@NotNullByDefault
public final class ChunkBaseWorldTools {
    /// Chunk Base application root.
    private static final String CHUNK_BASE_APPS_URL = "https://www.chunkbase.com/apps/";

    /// Oldest Java game version accepted by the restored tools.
    private static final GameVersionNumber MIN_GAME_VERSION = GameVersionNumber.asGameVersion("1.7");

    /// Oldest Java game version with End cities.
    private static final GameVersionNumber MIN_END_CITY_VERSION = GameVersionNumber.asGameVersion("1.13");

    /// Platform revisions supported by the seed-map application in descending compatibility order.
    private static final @Unmodifiable List<String> SEED_MAP_GAME_VERSIONS = List.of(
            "26.3", "26.2", "26.1", "1.21.9", "1.21.6", "1.21.5", "1.21.4",
            "1.21.2", "1.21", "1.20", "1.19.3", "1.19", "1.18", "1.17", "1.16",
            "1.15", "1.14", "1.13", "1.12", "1.11", "1.10", "1.9", "1.8", "1.7");

    /// Platform revisions supported by the stronghold finder in descending compatibility order.
    private static final @Unmodifiable List<String> STRONGHOLD_GAME_VERSIONS = List.of(
            "26.2", "26.1", "1.21.9", "1.21.6", "1.21.5", "1.21.4", "1.21.2",
            "1.21", "1.20", "1.19.3", "1.19", "1.18", "1.17", "1.16", "1.15",
            "1.14", "1.13", "1.12", "1.11", "1.10", "1.9", "1.8", "1.7");

    /// Platform revisions supported by the Nether fortress finder in descending compatibility order.
    private static final @Unmodifiable List<String> NETHER_FORTRESS_GAME_VERSIONS = List.of(
            "26.3", "26.2", "26.1", "1.21.9", "1.21.6", "1.21.5", "1.21.4",
            "1.21.2", "1.21", "1.20", "1.19.3", "1.19", "1.18", "1.17", "1.16",
            "1.15", "1.14", "1.13", "1.12", "1.11", "1.10", "1.9", "1.8", "1.7");

    /// Platform revisions supported by the End city finder in descending compatibility order.
    private static final @Unmodifiable List<String> END_CITY_GAME_VERSIONS = List.of(
            "26.2", "26.1", "1.21.9", "1.21.6", "1.21.5", "1.21.4", "1.21.2",
            "1.21", "1.20", "1.19.3", "1.19", "1.18", "1.17", "1.16", "1.15",
            "1.14", "1.13");

    /// Prevents instantiation of this stateless link builder.
    private ChunkBaseWorldTools() {
    }

    /// Returns whether a materialized row has a game version supported by Chunk Base.
    ///
    /// Seed availability is validated when the selected world is reopened off the EDT.
    ///
    /// @param item materialized world row
    /// @return whether its recorded game version is supported
    public static boolean supports(WorldCatalogItem item) {
        @Nullable String version = Objects.requireNonNull(item, "item").gameVersion();
        return version != null && GameVersionNumber.asGameVersion(version).compareTo(MIN_GAME_VERSION) >= 0;
    }

    /// Returns whether a materialized row supports the End city finder.
    ///
    /// @param item materialized world row
    /// @return whether its recorded version can contain End cities
    public static boolean supportsEndCity(WorldCatalogItem item) {
        @Nullable String version = Objects.requireNonNull(item, "item").gameVersion();
        return version != null && GameVersionNumber.asGameVersion(version).compareTo(MIN_END_CITY_VERSION) >= 0;
    }

    /// Builds the selected Chunk Base destination from a fully reopened Core world.
    ///
    /// @param world fully loaded world containing seed and version metadata
    /// @param tool selected destination
    /// @return immutable HTTPS destination
    /// @throws IllegalArgumentException when seed or version metadata is unavailable or unsupported
    public static URI createUri(World world, ChunkBaseTool tool) {
        World loadedWorld = Objects.requireNonNull(world, "world");
        @Nullable Long seed = loadedWorld.getSeed();
        @Nullable GameVersionNumber gameVersion = loadedWorld.getGameVersion();
        if (seed == null || gameVersion == null || gameVersion.compareTo(MIN_GAME_VERSION) < 0) {
            throw new IllegalArgumentException("Chunk Base requires a supported world seed and game version");
        }
        return createUri(seed, gameVersion, loadedWorld.isLargeBiomes(), tool);
    }

    /// Builds a deterministic Chunk Base destination from validated primitive world metadata.
    ///
    /// @param seed exact signed world seed
    /// @param gameVersion parsed game version
    /// @param largeBiomes whether the Overworld uses the large-biomes generator
    /// @param tool selected destination
    /// @return immutable HTTPS destination
    static URI createUri(
            long seed,
            GameVersionNumber gameVersion,
            boolean largeBiomes,
            ChunkBaseTool tool) {
        GameVersionNumber version = Objects.requireNonNull(gameVersion, "gameVersion");
        ChunkBaseTool selectedTool = Objects.requireNonNull(tool, "tool");
        if (version.compareTo(MIN_GAME_VERSION) < 0
                || selectedTool == ChunkBaseTool.END_CITY && version.compareTo(MIN_END_CITY_VERSION) < 0) {
            throw new IllegalArgumentException("The selected Chunk Base tool does not support this game version");
        }
        String application;
        @Unmodifiable List<String> supportedVersions;
        boolean useLargeBiomes;
        switch (selectedTool) {
            case SEED_MAP -> {
                application = "seed-map";
                supportedVersions = SEED_MAP_GAME_VERSIONS;
                useLargeBiomes = largeBiomes;
            }
            case STRONGHOLD -> {
                application = "stronghold-finder";
                supportedVersions = STRONGHOLD_GAME_VERSIONS;
                useLargeBiomes = largeBiomes;
            }
            case NETHER_FORTRESS -> {
                application = "nether-fortress-finder";
                supportedVersions = NETHER_FORTRESS_GAME_VERSIONS;
                useLargeBiomes = false;
            }
            case END_CITY -> {
                application = "endcity-finder";
                supportedVersions = END_CITY_GAME_VERSIONS;
                useLargeBiomes = false;
            }
            default -> throw new IllegalStateException("Unexpected Chunk Base tool: " + selectedTool);
        }
        String platform = compatiblePlatform(version, supportedVersions);
        String suffix = useLargeBiomes ? "_lb" : "";
        return URI.create(CHUNK_BASE_APPS_URL + application
                + "#seed=" + seed
                + "&platform=java_" + platform.replace('.', '_') + suffix);
    }

    /// Chooses the newest application platform not newer than the selected world version.
    ///
    /// @param gameVersion parsed world version
    /// @param supportedVersions descending application platform versions
    /// @return compatible non-blank application platform
    private static String compatiblePlatform(
            GameVersionNumber gameVersion,
            @Unmodifiable List<String> supportedVersions) {
        for (String candidate : supportedVersions) {
            if (gameVersion.compareTo(candidate) >= 0) {
                return candidate;
            }
        }
        return supportedVersions.get(supportedVersions.size() - 1);
    }
}
