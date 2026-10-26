// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;

import java.util.List;
import java.util.Map;

/// Launcher-owned listing and deletion capabilities for installed instance content.
@NotNullByDefault
public interface XYMLMcpContentOperations {
    /// Lists current managed resource packs.
    /// @param instanceId installed instance identifier
    /// @return unstarted task producing immutable items
    Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listResourcePacks(String instanceId);

    /// Removes exact managed resource packs using launcher-owned confirmation and recycle-bin policy.
    /// @param instanceId installed instance identifier
    /// @param paths absolute managed paths
    /// @return unstarted task producing approval, removed paths, remaining paths, and failures
    Task<@Unmodifiable Map<String, Object>> removeResourcePacks(String instanceId, @Unmodifiable List<String> paths);

    /// Lists current managed worlds.
    /// @param instanceId installed instance identifier
    /// @return unstarted task producing immutable items
    Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listWorlds(String instanceId);

    /// Removes exact managed worlds using launcher-owned confirmation and recycle-bin policy.
    /// @param instanceId installed instance identifier
    /// @param paths absolute managed paths
    /// @return unstarted task producing approval, removed paths, remaining paths, and failures
    Task<@Unmodifiable Map<String, Object>> removeWorlds(String instanceId, @Unmodifiable List<String> paths);

    /// Lists current managed shader packs.
    /// @param instanceId installed instance identifier
    /// @return unstarted task producing immutable items
    Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listShaderPacks(String instanceId);

    /// Removes exact managed shader packs using launcher-owned confirmation and recycle-bin policy.
    /// @param instanceId installed instance identifier
    /// @param paths absolute managed paths
    /// @return unstarted task producing approval, removed paths, remaining paths, and failures
    Task<@Unmodifiable Map<String, Object>> removeShaderPacks(String instanceId, @Unmodifiable List<String> paths);

    /// Lists saved server entries and their content revision.
    /// @param instanceId installed instance identifier
    /// @return unstarted task producing immutable entries and revision
    Task<@Unmodifiable Map<String, Object>> listServers(String instanceId);

    /// Removes exact saved entries only while the listed revision remains current.
    /// @param instanceId installed instance identifier
    /// @param indices zero-based entry indices
    /// @param revision revision returned by listServers
    /// @return unstarted task producing immutable approval and removed indices
    Task<@Unmodifiable Map<String, Object>> removeServers(
            String instanceId, @Unmodifiable List<Integer> indices, String revision);

    /// Lists folders and supported schematic files in one managed directory.
    /// @param instanceId installed instance identifier
    /// @param directory absolute managed directory, or null for the schematic root
    /// @return unstarted task producing immutable direct children
    Task<@Unmodifiable List<@Unmodifiable Map<String, Object>>> listSchematics(
            String instanceId, @Nullable String directory);

    /// Removes supported schematic files and directories, including directory contents.
    /// @param instanceId installed instance identifier
    /// @param paths absolute managed paths
    /// @return unstarted task producing immutable deletion outcomes
    Task<@Unmodifiable Map<String, Object>> removeSchematics(String instanceId, @Unmodifiable List<String> paths);
}
