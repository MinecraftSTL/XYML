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
import space.minecraftstl.xyml.event.Event;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceAutomaticIconResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Persists overview icon selections through the launcher's existing instance repository APIs.
@NotNullByDefault
final class RepositoryInstanceIconStore implements InstanceIconStore {
    /// Repository owning the target instance and its icon change event.
    private final XYMLGameRepository repository;

    /// Stable target instance identifier.
    private final GameInstanceID instanceId;

    /// Creates one repository-backed icon store.
    ///
    /// @param repository repository owning the instance
    /// @param instanceId stable non-blank instance identifier
    RepositoryInstanceIconStore(XYMLGameRepository repository, GameInstanceID instanceId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    /// Loads the custom file first and resolves `DEFAULT` to the instance's automatic bundled icon.
    ///
    /// @return current repository-backed icon state
    @Override
    public Snapshot load() {
        @Nullable GameSettings.Instance settings = repository.getInstanceGameSettings(instanceId);
        @Nullable GameInstanceIconType storedType = settings != null ? settings.iconProperty().getValue() : null;
        GameInstanceIconType builtInType = storedType == null || storedType == GameInstanceIconType.DEFAULT
                ? resolveAutomaticIconType()
                : storedType;
        @Nullable Path customImage = repository.getInstanceIconFile(instanceId).orElse(null);
        return new Snapshot(builtInType, customImage);
    }

    /// Resolves the same manifest-derived automatic icon used by the installed-instance list.
    ///
    /// @return detected loader icon, or the default icon when manifest analysis is unavailable
    private GameInstanceIconType resolveAutomaticIconType() {
        try {
            GameInstanceManifest manifest = repository.getInstanceManifest(instanceId);
            return InstanceAutomaticIconResolver.resolve(
                    repository.resolve(manifest),
                    repository.getGameVersion(manifest).orElse(null));
        } catch (RuntimeException failure) {
            LOG.warning("Failed to resolve automatic icon for instance " + instanceId, failure);
            return GameInstanceIconType.DEFAULT;
        }
    }

    /// Verifies writable settings, removes custom images, and persists the selected bundled type.
    ///
    /// @param iconType independently selectable bundled icon type
    /// @throws IOException when an existing custom image cannot be removed
    @Override
    public void selectBuiltIn(GameInstanceIconType iconType) throws IOException {
        InstanceIconChoice.BuiltIn selection = new InstanceIconChoice.BuiltIn(iconType);
        @Nullable GameSettings.Instance settings = repository.getInstanceGameSettingsOrCreate(instanceId);
        if (settings == null) {
            throw new IllegalStateException("Instance icon settings are unavailable or read-only");
        }

        repository.deleteIconFile(instanceId);
        if (repository.getInstanceIconFile(instanceId).isPresent()) {
            throw new IOException("The custom instance icon could not be removed");
        }
        settings.iconProperty().setValue(selection.iconType());
    }

    /// Copies one supported image and restores automatic bundled selection after custom-image removal.
    ///
    /// @param sourceImage local supported image
    /// @throws IOException when copying the image fails
    @Override
    public void selectCustom(Path sourceImage) throws IOException {
        repository.setInstanceIconFile(instanceId, Objects.requireNonNull(sourceImage, "sourceImage"));
        @Nullable GameSettings.Instance settings = repository.getInstanceGameSettingsOrCreate(instanceId);
        if (settings != null) {
            settings.iconProperty().setValue(GameInstanceIconType.DEFAULT);
        }
    }

    /// Deletes every custom icon and verifies that no supported variant remains.
    ///
    /// @throws IOException when at least one custom icon could not be removed
    @Override
    public void deleteCustom() throws IOException {
        repository.deleteIconFile(instanceId);
        if (repository.getInstanceIconFile(instanceId).isPresent()) {
            throw new IOException("The custom instance icon could not be removed");
        }
    }

    /// Fires the repository's established icon change event after successful persistence.
    ///
    /// @param source object responsible for the transition
    @Override
    public void publishChanged(Object source) {
        repository.onInstanceIconChanged.fireEvent(new Event(Objects.requireNonNull(source, "source")));
    }

    /// Validates one required non-blank identifier.
    ///
    /// @param value candidate identifier
    /// @param name parameter name
    /// @return validated identifier
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
