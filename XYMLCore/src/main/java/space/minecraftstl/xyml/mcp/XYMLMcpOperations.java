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

    /// Renames an installed instance through the launcher's repository lifecycle.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @return immutable resulting instance identifiers
    /// @throws IOException if validation or the repository mutation fails
    @Unmodifiable Map<String, Object> renameInstance(
            String sourceInstanceId, String destinationInstanceId) throws IOException;

    /// Duplicates an installed instance through the launcher's repository lifecycle.
    ///
    /// @param sourceInstanceId existing instance identifier
    /// @param destinationInstanceId new instance identifier
    /// @param copySaves whether the duplicate should include saved worlds
    /// @return immutable resulting instance identifiers and copy policy
    /// @throws IOException if validation or the repository mutation fails
    @Unmodifiable Map<String, Object> duplicateInstance(
            String sourceInstanceId, String destinationInstanceId, boolean copySaves) throws IOException;

    /// Deletes an installed instance after applying the launcher's deletion-confirmation policy.
    ///
    /// @param instanceId existing instance identifier
    /// @return immutable deletion outcome
    /// @throws IOException if the approved repository mutation fails
    @Unmodifiable Map<String, Object> deleteInstance(String instanceId) throws IOException;

    /// Returns the absolute mods directory for an instance.
    ///
    /// @param instanceId instance identifier
    /// @return absolute directory path
    String getModsDirectory(String instanceId);

    /// Reads one launcher-owned text resource.
    ///
    /// Supported URIs address the latest log, a crash-report directory listing, or one crash
    /// report file. The application implementation is responsible for validating filesystem
    /// ownership before returning text.
    ///
    /// @param uri resource URI
    /// @return immutable resource URI, MIME type, and text
    /// @throws IOException if the resource cannot be read
    @Unmodifiable Map<String, String> readResource(String uri) throws IOException;

    /// Analyzes a game crash using CrashReportAnalyzer and XYAT structured repair proposals.
    ///
    /// @param instanceId instance identifier
    /// @param logText optional raw log text
    /// @param crashReportPath optional crash-report path
    /// @return immutable merged analysis, report source, XYAT solutions, and non-fatal discovery warnings
    /// @throws IOException if an input file cannot be read
    @Unmodifiable Map<String, Object> analyzeCrash(
            String instanceId,
            @Nullable String logText,
            @Nullable String crashReportPath) throws IOException;

    /// Plans one repair solution returned by a previous crash analysis.
    ///
    /// @param analysisId server-issued crash-analysis identifier
    /// @param solutionId solution identifier from that analysis
    /// @return immutable repair plan or non-executable explanation
    @Unmodifiable Map<String, Object> planCrashSolution(String analysisId, String solutionId);

    /// Executes one server-issued crash-repair plan.
    ///
    /// The application implementation remains responsible for freshness checks, one-time plan
    /// consumption, and any launcher-owned confirmation required by the selected repair.
    ///
    /// @param planId server-issued repair-plan identifier
    /// @return immutable repair-operation status
    @Unmodifiable Map<String, Object> executeCrashSolution(String planId);

    /// Returns the current status of a crash-repair operation.
    ///
    /// @param operationId server-issued repair-operation identifier
    /// @return immutable operation status
    @Unmodifiable Map<String, Object> getCrashRepairStatus(String operationId);

    /// Requests cancellation of a crash-repair operation.
    ///
    /// @param operationId server-issued repair-operation identifier
    /// @return immutable operation status after the cancellation request
    @Unmodifiable Map<String, Object> cancelCrashRepair(String operationId);

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
    /// @param inherit whether all Java overrides should be removed
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setJavaVersion(
            String instanceId, @Nullable String javaVersion, @Nullable String javaPath, boolean inherit);

    /// Changes heap-memory bounds for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param minMemory optional minimum heap in MiB
    /// @param maxMemory optional maximum heap in MiB
    /// @param inherit whether both heap overrides should be removed
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setMemory(
            String instanceId, @Nullable Integer minMemory, @Nullable Integer maxMemory, boolean inherit);

    /// Replaces JVM options for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param options JVM options, or null when inheritance is requested
    /// @param inherit whether the JVM-options override should be removed
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setJvmOptions(
            String instanceId, @Nullable String options, boolean inherit);

    /// Changes window settings for an instance.
    ///
    /// @param instanceId instance identifier
    /// @param width optional width
    /// @param height optional height
    /// @param fullscreen optional fullscreen state
    /// @param inherit whether all window overrides should be removed
    /// @return immutable resulting settings
    @Unmodifiable Map<String, Object> setWindowOptions(
            String instanceId,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Boolean fullscreen,
            boolean inherit);

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
    /// @return immutable approval state and removed paths
    /// @throws IOException if a selected mod cannot be removed
    @Unmodifiable Map<String, Object> removeMods(String instanceId, List<String> paths) throws IOException;

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

}
