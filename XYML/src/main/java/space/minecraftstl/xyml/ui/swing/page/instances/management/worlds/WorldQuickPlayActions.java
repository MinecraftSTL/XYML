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
import space.minecraftstl.xyml.game.launch.LaunchSession;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

/// Binds one instance's world-folder names to the existing launch and launch-script command chain.
///
/// Callers create an available boundary only after binding stable account, game-directory, and instance identity.
/// Implementations must return promptly: process and script preparation belong to their existing background services.
@NotNullByDefault
public final class WorldQuickPlayActions {
    /// Shared unavailable boundary for catalog contexts that intentionally omit quick-play controls.
    private static final WorldQuickPlayActions UNAVAILABLE = new WorldQuickPlayActions(
            false,
            worldFolder -> {
                throw new IllegalStateException("World quick play is unavailable");
            },
            (worldFolder, destination) -> CompletableFuture.failedFuture(
                    new IllegalStateException("World quick-play script export is unavailable")));

    /// Whether the owner supplied complete production launch bindings.
    private final boolean available;

    /// Non-blocking command starting ordinary launch preparation for one exact world folder.
    private final Function<String, LaunchSession> launchCommand;

    /// Non-blocking command starting launch-script preparation for one exact world folder and destination.
    private final BiFunction<String, Path, CompletionStage<Path>> launchScriptCommand;

    /// Creates one validated action boundary.
    ///
    /// @param available whether actions may be invoked
    /// @param launchCommand process-launch callback
    /// @param launchScriptCommand launch-script callback
    private WorldQuickPlayActions(
            boolean available,
            Function<String, LaunchSession> launchCommand,
            BiFunction<String, Path, CompletionStage<Path>> launchScriptCommand) {
        this.available = available;
        this.launchCommand = Objects.requireNonNull(launchCommand, "launchCommand");
        this.launchScriptCommand = Objects.requireNonNull(launchScriptCommand, "launchScriptCommand");
    }

    /// Creates available quick-play actions around callbacks already bound to one managed instance.
    ///
    /// @param launchCommand callback receiving the exact world directory name
    /// @param launchScriptCommand callback receiving the exact world directory name and local script destination
    /// @return available action boundary
    public static WorldQuickPlayActions available(
            Function<String, LaunchSession> launchCommand,
            BiFunction<String, Path, CompletionStage<Path>> launchScriptCommand) {
        return new WorldQuickPlayActions(true, launchCommand, launchScriptCommand);
    }

    /// Returns an unavailable boundary for callers that have not supplied launcher commands.
    ///
    /// @return shared unavailable action boundary
    public static WorldQuickPlayActions unavailable() {
        return UNAVAILABLE;
    }

    /// Reports whether quick-play controls should be enabled for valid world selections.
    ///
    /// @return true when both bound commands are available
    public boolean available() {
        return available;
    }

    /// Starts background process preparation for one validated, unlocked world.
    ///
    /// @param world exact materialized world row
    /// @return launch session from the existing game-launch service
    public LaunchSession launch(WorldCatalogItem world) {
        WorldCatalogItem selectedWorld = requireLaunchable(world);
        requireAvailable();
        return Objects.requireNonNull(
                launchCommand.apply(selectedWorld.directoryName()),
                "world quick-play command returned null session");
    }

    /// Starts background standalone-script generation for one validated, unlocked world.
    ///
    /// @param world exact materialized world row
    /// @param destination local script destination
    /// @return completion yielding the generated normalized script path
    public CompletionStage<Path> exportLaunchScript(WorldCatalogItem world, Path destination) {
        WorldCatalogItem selectedWorld = requireLaunchable(world);
        Path normalizedDestination = Objects.requireNonNull(destination, "destination")
                .toAbsolutePath()
                .normalize();
        requireAvailable();
        return Objects.requireNonNull(
                launchScriptCommand.apply(selectedWorld.directoryName(), normalizedDestination),
                "world quick-play script command returned null stage");
    }

    /// Rejects invocations through the unavailable boundary.
    private void requireAvailable() {
        if (!available) {
            throw new IllegalStateException("World quick play is unavailable");
        }
    }

    /// Rejects unreadable or locked rows before invoking launcher callbacks.
    ///
    /// @param world candidate selected row
    /// @return validated row
    private static WorldCatalogItem requireLaunchable(WorldCatalogItem world) {
        WorldCatalogItem selectedWorld = Objects.requireNonNull(world, "world");
        if (!selectedWorld.readable()) {
            throw new IllegalArgumentException("Unreadable worlds cannot be launched");
        }
        if (selectedWorld.locked()) {
            throw new IllegalStateException("Locked worlds cannot be launched");
        }
        return selectedWorld;
    }
}
