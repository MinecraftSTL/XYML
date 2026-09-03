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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;

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
    /// @return unstarted task producing immutable instance summaries
    Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listInstances();

    /// Returns effective settings for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return unstarted task producing immutable settings
    Task<@Unmodifiable Map<String, Object>> getInstanceSettings(String instanceId);

    /// Renames an installed instance through the launcher's repository lifecycle.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @return unstarted task producing immutable resulting instance identifiers
    Task<@Unmodifiable Map<String, Object>> renameInstance(
            String sourceInstanceId, String destinationInstanceId);

    /// Duplicates an installed instance through the launcher's repository lifecycle.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @param copySaves whether the duplicate should include saved worlds
    /// @return unstarted task producing immutable resulting instance identifiers and copy policy
    Task<@Unmodifiable Map<String, Object>> duplicateInstance(
            String sourceInstanceId, String destinationInstanceId, boolean copySaves);

    /// Deletes an installed instance after applying the launcher's deletion-confirmation policy.
    ///
    /// @param instanceId existing instance identifier
    /// @return unstarted task producing an immutable deletion outcome
    Task<@Unmodifiable Map<String, Object>> deleteInstance(String instanceId);

    /// Returns the absolute mods directory for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return unstarted task producing the absolute directory path
    Task<String> getModsDirectory(String instanceId);

    /// Reads one launcher-owned text resource.
    ///
    /// Supported URIs address the latest log, a crash-report directory listing, or one crash
    /// report file. The application implementation is responsible for validating filesystem
    /// ownership before returning text.
    ///
    /// @param uri resource URI
    /// @return unstarted task producing immutable resource URI, MIME type, and text
    Task<@Unmodifiable Map<String, String>> readResource(String uri);

    /// Analyzes a game crash using XYML's crash analyzer.
    ///
    /// @param instanceId instance identifier
    /// @param logText optional raw log text
    /// @param crashReportPath optional crash-report path
    /// @return unstarted task producing immutable merged analysis, report source, and discovery warnings
    Task<@Unmodifiable Map<String, Object>> analyzeCrash(
            String instanceId,
            @Nullable String logText,
            @Nullable String crashReportPath);

    /// Lists Java runtimes known to the launcher.
    ///
    /// @return immutable runtime summaries
    /// @throws InterruptedException if runtime discovery is interrupted
    @Unmodifiable List<@Unmodifiable Map<String, Object>> listJavaRuntimes() throws InterruptedException;

    /// Lists local mods and their enabled state.
    ///
    /// @param instanceId instance identifier
    /// @return unstarted task producing immutable mod summaries
    Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listLocalMods(String instanceId);

    /// Changes the Java selection for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param javaVersion optional Java major version
    /// @param javaPath optional Java executable path
    /// @param inherit whether all Java overrides should be removed
    /// @return unstarted task producing immutable resulting settings
    Task<@Unmodifiable Map<String, Object>> setJavaVersion(
            String instanceId, @Nullable String javaVersion, @Nullable String javaPath, boolean inherit);

    /// Changes heap-memory bounds for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param minMemory optional minimum heap in MiB
    /// @param maxMemory optional maximum heap in MiB
    /// @param inherit whether both heap overrides should be removed
    /// @return unstarted task producing immutable resulting settings
    Task<@Unmodifiable Map<String, Object>> setMemory(
            String instanceId, @Nullable Integer minMemory, @Nullable Integer maxMemory, boolean inherit);

    /// Replaces JVM options for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param options JVM options, or null when inheritance is requested
    /// @param inherit whether the JVM-options override should be removed
    /// @return unstarted task producing immutable resulting settings
    Task<@Unmodifiable Map<String, Object>> setJvmOptions(
            String instanceId, @Nullable String options, boolean inherit);

    /// Changes window settings for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param width optional width
    /// @param height optional height
    /// @param fullscreen optional fullscreen state
    /// @param inherit whether all window overrides should be removed
    /// @return unstarted task producing immutable resulting settings
    Task<@Unmodifiable Map<String, Object>> setWindowOptions(
            String instanceId,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Boolean fullscreen,
            boolean inherit);

    /// Enables a local mod.
    ///
    /// @param instanceId target instance
    /// @param path mod path
    /// @return unstarted task producing the resulting mod path
    Task<String> enableMod(String instanceId, String path);

    /// Disables a local mod.
    ///
    /// @param instanceId target instance
    /// @param path mod path
    /// @return unstarted task producing the resulting mod path
    Task<String> disableMod(String instanceId, String path);

    /// Removes selected local mods.
    ///
    /// @param instanceId target instance
    /// @param paths selected mod paths
    /// @return unstarted task producing immutable approval state and removed paths
    Task<@Unmodifiable Map<String, Object>> removeMods(String instanceId, @Unmodifiable List<String> paths);

    /// Launches a game instance for testing.
    ///
    /// @param instanceId target instance
    /// @return unstarted task producing an immutable launch result
    Task<@Unmodifiable Map<String, Object>> launchGame(String instanceId);

    /// Stops a tracked game process.
    ///
    /// @param instanceId target instance
    /// @return unstarted task producing an immutable stop result
    Task<@Unmodifiable Map<String, Object>> stopGame(String instanceId);

    /// Returns status for a tracked launch.
    ///
    /// @param instanceId target instance
    /// @return immutable launch status
    @Unmodifiable Map<String, Object> getLaunchStatus(String instanceId);

}
