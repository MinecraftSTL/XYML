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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;

import java.io.IOException;
import java.util.Objects;

/// Adapts the established `GameRepository` and `XYMLGameRepository` lifecycle APIs for Swing.
///
/// Disk mutations deliberately delegate to the existing repository implementation: rename uses the
/// `GameRepository` contract, while duplicate and removal use XYML's instance-aware methods. Each
/// successful mutation synchronously refreshes the repository on the caller's background thread so the
/// instance-list model receives an authoritative `RefreshedInstancesEvent` before the management page exits.
@NotNullByDefault
public final class RepositoryInstanceLifecycleService implements InstanceLifecycleService {
    /// Repository owning the instance files and persistent selection state.
    private final XYMLGameRepository repository;

    /// Creates a lifecycle service backed by one real XYML repository.
    ///
    /// @param repository repository containing managed instances
    public RepositoryInstanceLifecycleService(XYMLGameRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /// Returns whether a candidate satisfies XYML's existing portable instance-ID rule.
    ///
    /// @param destinationId candidate destination identifier
    /// @return whether the identifier is safe for an instance directory
    @Override
    public boolean isValidDestinationId(String destinationId) {
        return XYMLGameRepository.isValidInstanceId(Objects.requireNonNull(destinationId, "destinationId"));
    }

    /// Renames an existing instance through `GameRepository.renameInstance` and refreshes the index.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @throws IOException when the target conflicts or rename reports failure
    @Override
    public void rename(String sourceId, String destinationId) throws IOException {
        String source = requireNonBlank(sourceId, "sourceId");
        String destination = requireDestination(destinationId);
        if (source.equals(destination)) {
            throw new IOException("The new instance name must differ from the current name");
        }
        requireDestinationAvailable(source, destination);
        GameRepository gameRepository = repository;
        if (!gameRepository.renameInstance(source, destination)) {
            throw new IOException("The instance could not be renamed");
        }
        repository.refreshInstances();
    }

    /// Copies an instance through the repository's established copy routine and refreshes the index.
    ///
    /// @param sourceId stable existing source identifier
    /// @param destinationId validated target identifier
    /// @param copySaves whether source worlds should be copied
    /// @throws IOException when the source is missing, destination conflicts, or copying fails
    @Override
    public void duplicate(String sourceId, String destinationId, boolean copySaves) throws IOException {
        String source = requireNonBlank(sourceId, "sourceId");
        String destination = requireDestination(destinationId);
        if (source.equals(destination)) {
            throw new IOException("The duplicate instance name must differ from the source name");
        }
        requireDestinationAvailable(source, destination);
        repository.duplicateInstance(source, destination, copySaves);
        repository.refreshInstances();
    }

    /// Removes an instance through XYML's recycle-bin-aware removal routine and refreshes the index.
    ///
    /// @param sourceId stable existing source identifier
    /// @throws IOException when removal reports failure
    @Override
    public void delete(String sourceId) throws IOException {
        String source = requireNonBlank(sourceId, "sourceId");
        if (!repository.removeInstanceFromDisk(source)) {
            throw new IOException("The instance could not be deleted");
        }
        repository.refreshInstances();
    }

    /// Reconciles the repository's persisted selection on the Swing event dispatch thread.
    ///
    /// @param preferredId renamed or duplicated instance ID, or `null` after deletion
    @Override
    public void reconcileSelection(@Nullable String preferredId) {
        if (preferredId != null && repository.hasVersion(preferredId)) {
            repository.setSelectedInstance(preferredId);
        } else {
            repository.refreshSelectedInstance();
        }
    }

    /// Validates a destination ID before it reaches a filesystem operation.
    ///
    /// @param destinationId requested destination identifier
    /// @return normalized non-blank destination identifier
    /// @throws IOException when the identifier is invalid
    private String requireDestination(String destinationId) throws IOException {
        String destination = requireNonBlank(destinationId, "destinationId");
        if (!isValidDestinationId(destination)) {
            throw new IOException("The instance name is invalid");
        }
        return destination;
    }

    /// Rejects a destination already known by the loaded repository before a destructive mutation begins.
    ///
    /// @param source current source identifier
    /// @param destination requested destination identifier
    /// @throws IOException when another instance already owns the destination ID
    private void requireDestinationAvailable(String source, String destination) throws IOException {
        if (!source.equals(destination) && repository.instanceIdConflicts(destination)) {
            throw new IOException("An instance with that name already exists");
        }
    }

    /// Validates a stable non-blank identifier while preserving its exact filesystem spelling.
    ///
    /// @param value candidate identifier
    /// @param name parameter name
    /// @return validated identifier
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }
}
