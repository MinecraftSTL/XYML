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
import space.minecraftstl.xyml.game.World;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.nio.file.Path;
import java.util.Objects;

/// Viewport-materialized metadata for one direct child of an instance `saves` directory.
///
/// A shallow index intentionally does not construct this type. The catalog creates it only for
/// rows requested by the visible viewport, so a large collection of world NBT files never blocks
/// opening the instance-management page.
///
/// @param path normalized direct-child world directory
/// @param directoryName local directory name used as a resilient fallback label
/// @param worldName stored level name, or an empty string when the directory was unreadable
/// @param lastPlayed stored epoch milliseconds, or zero when the directory was unreadable
/// @param gameVersion parsed game-version display text, or `null` when not recorded
/// @param locked whether the world currently appears locked
/// @param failureDetail load detail, or `null` when Core successfully read the world
/// @param details viewport-loaded world information, or `null` for unreadable and compatibility rows
@NotNullByDefault
public record WorldCatalogItem(
        Path path,
        String directoryName,
        String worldName,
        long lastPlayed,
        @Nullable String gameVersion,
        boolean locked,
        @Nullable String failureDetail,
        @Nullable WorldCatalogDetails details) {
    /// Normalizes the durable path and validates row identity values.
    public WorldCatalogItem {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        directoryName = requireNonBlank(directoryName, "directoryName");
        worldName = Objects.requireNonNull(worldName, "worldName");
        if (gameVersion != null && gameVersion.isBlank()) {
            throw new IllegalArgumentException("gameVersion must not be blank when present");
        }
        if (failureDetail != null && failureDetail.isBlank()) {
            throw new IllegalArgumentException("failureDetail must not be blank when present");
        }
    }

    /// Creates a compatibility row without an expanded detail snapshot.
    ///
    /// This constructor keeps deterministic catalog and neighboring page tests concise. Production
    /// rows created by [#loaded(World)] always include complete viewport-loaded details.
    ///
    /// @param path normalized direct-child world directory
    /// @param directoryName local directory name
    /// @param worldName stored level name
    /// @param lastPlayed stored epoch milliseconds
    /// @param gameVersion optional recorded game version
    /// @param locked current lock state
    /// @param failureDetail optional load failure
    public WorldCatalogItem(
            Path path,
            String directoryName,
            String worldName,
            long lastPlayed,
            @Nullable String gameVersion,
            boolean locked,
            @Nullable String failureDetail) {
        this(path, directoryName, worldName, lastPlayed, gameVersion, locked, failureDetail, null);
    }

    /// Creates a successfully decoded row from the Core world API.
    ///
    /// @param world fully loaded Core world
    /// @return immutable metadata suitable for a visible viewport row
    /// @throws java.io.IOException when the decoded icon cannot cross the immutable UI boundary
    public static WorldCatalogItem loaded(World world) throws java.io.IOException {
        World loadedWorld = Objects.requireNonNull(world, "world");
        @Nullable GameVersionNumber version = loadedWorld.getGameVersion();
        return new WorldCatalogItem(
                loadedWorld.getFile(),
                loadedWorld.getFileName(),
                loadedWorld.getWorldName(),
                loadedWorld.getLastPlayed(),
                version == null ? null : version.toString(),
                loadedWorld.isLocked(),
                null,
                WorldCatalogDetailsCodec.read(loadedWorld));
    }

    /// Creates a readable-path placeholder for a directory whose world metadata cannot be parsed.
    ///
    /// The path remains openable so users can inspect or repair it, but destructive World API
    /// commands stay disabled because Core could not validate the directory.
    ///
    /// @param path normalized direct-child directory
    /// @param failure loading failure
    /// @return immutable unreadable row
    public static WorldCatalogItem unreadable(Path path, Throwable failure) {
        Path normalizedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Throwable actualFailure = Objects.requireNonNull(failure, "failure");
        return new WorldCatalogItem(
                normalizedPath,
                directoryName(normalizedPath),
                "",
                0L,
                null,
                false,
                failureDetail(actualFailure),
                null);
    }

    /// Returns whether Core successfully decoded this world directory.
    ///
    /// @return true only for a fully validated world row
    public boolean readable() {
        return failureDetail == null;
    }

    /// Returns the most useful stable list label.
    ///
    /// @return stored name when available, otherwise the directory name
    public String displayText() {
        return worldName.isBlank() ? directoryName : worldName;
    }

    /// Extracts a non-blank final directory segment.
    ///
    /// @param directory normalized world directory
    /// @return directory name used for display and durable fallback identity
    private static String directoryName(Path directory) {
        @Nullable Path fileName = directory.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("World directory must have a name: " + directory);
        }
        return requireNonBlank(fileName.toString(), "directoryName");
    }

    /// Converts one exception into concise visible detail.
    ///
    /// @param failure original parse failure
    /// @return exception message or simple type name
    private static String failureDetail(Throwable failure) {
        @Nullable String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /// Rejects blank durable labels.
    ///
    /// @param value candidate text
    /// @param name parameter name for diagnostics
    /// @return validated value
    private static String requireNonBlank(String value, String name) {
        String checkedValue = Objects.requireNonNull(value, name);
        if (checkedValue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checkedValue;
    }
}
