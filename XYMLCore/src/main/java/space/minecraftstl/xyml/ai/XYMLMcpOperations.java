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
package space.minecraftstl.xyml.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/// Defines launcher operations exposed through the Core-owned MCP protocol adapter.
///
/// Implementations may live in the application module when they need initialized settings or
/// repository types that intentionally remain outside XYMLCore.
@NotNullByDefault
public interface XYMLMcpOperations {

    /// Lists installed game instances.
    ///
    /// @return immutable instance summaries
    @Unmodifiable List<Map<String, Object>> listInstances();

    /// Returns effective settings for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return immutable settings
    @Unmodifiable Map<String, Object> getInstanceSettings(String instanceId);

    /// Returns the absolute mods directory for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return absolute directory path
    String getModsDirectory(String instanceId);

    /// Reads the latest game log tail.
    ///
    /// @param instanceId instance identifier
    /// @param requestedLines maximum trailing lines to return
    /// @return immutable log data
    /// @throws IOException if the log cannot be read
    @Unmodifiable Map<String, Object> getLogs(String instanceId, int requestedLines) throws IOException;

    /// Analyzes a game crash using XYML's crash analyzer.
    ///
    /// @param instanceId instance identifier
    /// @param logText optional raw log text
    /// @param crashReportPath optional crash-report path
    /// @return immutable analysis data
    /// @throws IOException if an input file cannot be read
    @Unmodifiable Map<String, Object> analyzeCrash(
            String instanceId,
            @Nullable String logText,
            @Nullable String crashReportPath) throws IOException;

    /// Lists Java runtimes known to the launcher.
    ///
    /// @return immutable runtime summaries
    /// @throws InterruptedException if runtime discovery is interrupted
    @Unmodifiable List<Map<String, Object>> listJavaRuntimes() throws InterruptedException;

    /// Lists local mods and their enabled state.
    ///
    /// @param instanceId instance identifier
    /// @return immutable mod summaries
    /// @throws IOException if the mods directory cannot be read
    @Unmodifiable List<Map<String, Object>> listLocalMods(String instanceId) throws IOException;

    /// Changes the Java selection for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param javaVersion optional Java major version
    /// @param javaPath optional Java executable path
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setJavaVersion(
            String instanceId, @Nullable String javaVersion, @Nullable String javaPath);

    /// Changes heap-memory bounds for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param minMemory optional minimum heap in MiB
    /// @param maxMemory optional maximum heap in MiB
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setMemory(
            String instanceId, @Nullable Integer minMemory, @Nullable Integer maxMemory);

    /// Replaces JVM options for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param options JVM options
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setJvmOptions(String instanceId, String options);

    /// Changes window settings for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param width optional width
    /// @param height optional height
    /// @param fullscreen optional fullscreen state
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setWindowOptions(
            String instanceId, @Nullable Integer width, @Nullable Integer height, @Nullable Boolean fullscreen);

    /// Enables a local mod.
    ///
    /// @param instanceId target instance
    /// @param path mod path
    /// @return resulting mod path
    /// @throws IOException if the mod cannot be renamed
    String enableMod(String instanceId, String path) throws IOException;

    /// Disables a local mod.
    ///
    /// @param instanceId target instance
    /// @param path mod path
    /// @return resulting mod path
    /// @throws IOException if the mod cannot be renamed
    String disableMod(String instanceId, String path) throws IOException;

    /// Removes selected local mods.
    ///
    /// @param instanceId target instance
    /// @param paths selected mod paths
    /// @return immutable removed paths
    /// @throws IOException if a selected mod cannot be removed
    @Unmodifiable List<String> removeMods(String instanceId, List<String> paths) throws IOException;

    /// Launches a game instance for testing.
    ///
    /// @param instanceId target instance
    /// @return immutable launch result
    /// @throws Exception if the launch cannot start
    @Unmodifiable Map<String, Object> launchGame(String instanceId) throws Exception;

    /// Stops a tracked game process.
    ///
    /// @param instanceId target instance
    /// @return immutable stop result
    @Unmodifiable Map<String, Object> stopGame(String instanceId);

    /// Returns status for a tracked launch.
    ///
    /// @param instanceId target instance
    /// @return immutable launch status
    @Unmodifiable Map<String, Object> getLaunchStatus(String instanceId);

    /// Reads a supported XYML resource URI.
    ///
    /// @param uri resource URI
    /// @return immutable resource data
    /// @throws IOException if the resource cannot be read
    @Unmodifiable Map<String, String> readResource(String uri) throws IOException;
}
