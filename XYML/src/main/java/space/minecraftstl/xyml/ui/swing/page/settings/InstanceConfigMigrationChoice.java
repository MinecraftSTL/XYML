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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Identifies one selectable configuration-migration source instance and its owning repository.
///
/// @param repository owning repository, or null for a persisted unavailable placeholder
/// @param gameDirectoryId stable game-directory identifier
/// @param instanceId stable instance identifier
/// @param label user-visible directory and instance label
/// @param isolated whether the currently loaded source is isolated
/// @param available whether the source currently resolves to an installed instance
@NotNullByDefault
public record InstanceConfigMigrationChoice(
        @Nullable XYMLGameRepository repository,
        GameDirectoryID gameDirectoryId,
        GameInstanceID instanceId,
        String label,
        boolean isolated,
        boolean available) {
    /// Validates stable source identity and display text.
    public InstanceConfigMigrationChoice {
        Objects.requireNonNull(gameDirectoryId, "gameDirectoryId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(label, "label");
    }

    /// Returns every currently loaded instance, optionally restricted to isolated sources and excluding one target.
    ///
    /// @param isolatedOnly whether non-isolated sources are excluded
    /// @param targetRepository target repository, or null when no target is fixed
    /// @param targetInstance target instance, or null when no target is fixed
    /// @return immutable source choices
    public static @Unmodifiable List<InstanceConfigMigrationChoice> availableChoices(
            boolean isolatedOnly,
            @Nullable XYMLGameRepository targetRepository,
            @Nullable GameInstanceID targetInstance) {
        EdtDispatcher.requireEventDispatchThread();
        List<InstanceConfigMigrationChoice> choices = new ArrayList<>();
        for (GameDirectory gameDirectory : GameDirectoryManager.getGameDirectories()) {
            XYMLGameRepository repository = GameDirectoryManager.getRepository(gameDirectory.getId());
            for (GameInstanceManifest manifest : repository.getDisplayInstanceManifests().toList()) {
                GameInstanceID instanceId = manifest.id();
                if (repository == targetRepository && instanceId.equals(targetInstance)) {
                    continue;
                }
                boolean isolated = repository.isInstanceIsolated(instanceId);
                if (isolatedOnly && !isolated) {
                    continue;
                }
                choices.add(new InstanceConfigMigrationChoice(
                        repository,
                        gameDirectory.getId(),
                        instanceId,
                        gameDirectory.getPath() + " / " + instanceId.id(),
                        isolated,
                        true));
            }
        }
        return List.copyOf(choices);
    }

    /// Creates a visible placeholder for a persisted source that is no longer available.
    ///
    /// @param gameDirectoryId persisted game-directory identifier
    /// @param instanceId persisted instance identifier
    /// @return unavailable placeholder choice
    public static InstanceConfigMigrationChoice unavailable(
            GameDirectoryID gameDirectoryId,
            GameInstanceID instanceId) {
        return new InstanceConfigMigrationChoice(
                null,
                gameDirectoryId,
                instanceId,
                i18n("settings.instance_config_migration.source_unavailable_label", instanceId.id(), gameDirectoryId),
                false,
                false);
    }

    /// Returns the user-visible source label.
    @Override
    public String toString() {
        return label;
    }
}
