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
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameSettings;

import java.util.Objects;

/// Adapts repository-owned instance settings to the complete Swing settings snapshot contract.
@NotNullByDefault
public final class RepositoryInstanceGameSettingsStore implements InstanceGameSettingsStore {
    /// Repository containing the managed instance and its durable instance settings file.
    private final XYMLGameRepository repository;

    /// Stable non-blank instance identifier represented by this store.
    private final GameInstanceID instanceId;

    /// Creates an adapter for one instance in the given repository.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    public RepositoryInstanceGameSettingsStore(XYMLGameRepository repository, GameInstanceID instanceId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    /// Reads all effective values together with each property's independent local inheritance state.
    ///
    /// @return current complete settings snapshot
    @Override
    public InstanceGameSettingsSnapshot snapshot() {
        @Nullable GameSettings.Instance instance = repository.getInstanceGameSettings(instanceId);
        return InstanceGameSettingsMapper.snapshot(
                repository.hasInstance(instanceId) && !repository.isInstanceGameSettingsReadOnly(instanceId),
                instance,
                repository.getEffectiveGameSettings(instanceId));
    }

    /// Persists a complete snapshot without merging unrelated override markers.
    ///
    /// @param snapshot validated values and inheritance choices to persist
    /// @throws IllegalStateException when this instance is unknown or its settings file is read-only
    @Override
    public void save(InstanceGameSettingsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.writable() || repository.isInstanceGameSettingsReadOnly(instanceId)) {
            throw new IllegalStateException("Instance game settings are read-only");
        }
        @Nullable GameSettings.Instance settings = repository.getInstanceGameSettingsOrCreate(instanceId);
        if (settings == null) {
            throw new IllegalStateException("Instance game settings are unavailable");
        }

        InstanceGameSettingsMapper.apply(settings, snapshot);
        repository.saveGameSettings(instanceId);
    }

    /// Rejects a blank identifier before it can address a repository directory.
    ///
    /// @param value candidate instance identifier
    /// @param name parameter name used in the exception message
    /// @return validated identifier
    private static String requireNonBlank(String value, String name) {
        String checkedValue = Objects.requireNonNull(value, name);
        if (checkedValue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checkedValue;
    }
}
