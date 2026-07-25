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
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameSettings;

import java.util.Objects;

/// Adapts `XYMLGameRepository` instance settings into the Swing settings-page store contract.
///
/// Saving only changes the four setting groups represented by [InstanceGameSettingsSnapshot]. It explicitly adds or
/// removes their property names from `overrideProperties`, so inherited preset values remain inherited until a user
/// enables the corresponding local control.
@NotNullByDefault
public final class RepositoryInstanceGameSettingsStore implements InstanceGameSettingsStore {
    /// Repository containing the managed instance and its durable instance settings file.
    private final XYMLGameRepository repository;

    /// Stable non-blank instance identifier represented by this store.
    private final String instanceId;

    /// Creates an adapter for one instance in the given repository.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    public RepositoryInstanceGameSettingsStore(XYMLGameRepository repository, String instanceId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = requireNonBlank(instanceId, "instanceId");
    }

    /// Reads effective values together with their local inheritance state.
    ///
    /// @return current editable snapshot
    @Override
    public InstanceGameSettingsSnapshot snapshot() {
        @Nullable GameSettings.Instance instance = repository.getInstanceGameSettings(instanceId);
        GameSettings.Effective effective = repository.getEffectiveGameSettings(instanceId);
        @Nullable Integer maximumMemory = effective.getInheritable(GameSettings::maxMemoryProperty);
        int maximumMemoryMiB = maximumMemory != null && maximumMemory > 0
                ? maximumMemory
                : GameSettings.SUGGESTED_MEMORY;
        return new InstanceGameSettingsSnapshot(
                repository.hasVersion(instanceId) && !repository.isInstanceGameSettingsReadOnly(instanceId),
                isAnyOverridden(instance, GameSettings.PROPERTY_AUTO_MEMORY, GameSettings.PROPERTY_MAX_MEMORY),
                effective.getInheritable(GameSettings::autoMemoryProperty),
                maximumMemoryMiB,
                isAnyOverridden(
                        instance,
                        GameSettings.PROPERTY_JAVA_TYPE,
                        GameSettings.PROPERTY_CUSTOM_JAVA_VERSION,
                        GameSettings.PROPERTY_CUSTOM_JAVA_PATH,
                        GameSettings.PROPERTY_DETECTED_JAVA),
                effective.getInheritable(GameSettings::javaTypeProperty),
                effective.getInheritable(GameSettings::customJavaVersionProperty),
                effective.getInheritable(GameSettings::customJavaPathProperty),
                !effective.getInheritable(GameSettings::detectedJavaProperty).isEmpty(),
                isOverridden(instance, GameSettings.PROPERTY_JVM_OPTIONS),
                effective.getInheritable(GameSettings::jvmOptionsProperty),
                isOverridden(instance, GameSettings.PROPERTY_RUNNING_DIRECTORY),
                effective.getInheritable(GameSettings::runningDirectoryProperty));
    }

    /// Persists values and removes override markers for groups that should continue inheriting from the preset.
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
        if (snapshot.javaOverridden()
                && snapshot.javaVersionType() == space.minecraftstl.xyml.setting.JavaVersionType.DETECTED
                && !snapshot.detectedJavaAvailable()) {
            throw new IllegalArgumentException("No detected Java runtime is available for this instance");
        }

        applyMemory(settings, snapshot);
        applyJava(settings, snapshot);
        applyJvmOptions(settings, snapshot);
        applyRunningDirectory(settings, snapshot);
        repository.saveGameSettings(instanceId);
    }

    /// Applies the automatic and maximum-memory properties as one inherited setting group.
    ///
    /// @param settings mutable local instance settings
    /// @param snapshot edited UI state
    private static void applyMemory(GameSettings.Instance settings, InstanceGameSettingsSnapshot snapshot) {
        if (snapshot.memoryOverridden()) {
            settings.autoMemoryProperty().setValue(snapshot.automaticMemory());
            settings.maxMemoryProperty().setValue(snapshot.maximumMemoryMiB());
            settings.getOverrideProperties().add(GameSettings.PROPERTY_AUTO_MEMORY);
            settings.getOverrideProperties().add(GameSettings.PROPERTY_MAX_MEMORY);
        } else {
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_AUTO_MEMORY);
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_MAX_MEMORY);
        }
    }

    /// Applies Java selection strategy values as one inherited setting group.
    ///
    /// The detected Java reference is intentionally retained when this group is local because selection of detected
    /// runtimes belongs to the local Java-management page and must not be guessed from a text field.
    ///
    /// @param settings mutable local instance settings
    /// @param snapshot edited UI state
    private static void applyJava(GameSettings.Instance settings, InstanceGameSettingsSnapshot snapshot) {
        if (snapshot.javaOverridden()) {
            settings.javaTypeProperty().setValue(snapshot.javaVersionType());
            settings.customJavaVersionProperty().setValue(snapshot.customJavaVersion());
            settings.customJavaPathProperty().setValue(snapshot.customJavaPath());
            settings.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
            settings.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
            settings.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
        } else {
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_JAVA_TYPE);
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_DETECTED_JAVA);
        }
    }

    /// Applies free-form JVM arguments as one inherited setting group.
    ///
    /// @param settings mutable local instance settings
    /// @param snapshot edited UI state
    private static void applyJvmOptions(GameSettings.Instance settings, InstanceGameSettingsSnapshot snapshot) {
        if (snapshot.jvmOptionsOverridden()) {
            settings.jvmOptionsProperty().setValue(snapshot.jvmOptions());
            settings.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
        } else {
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_JVM_OPTIONS);
        }
    }

    /// Applies the working-directory source selection and custom path.
    ///
    /// A local empty path intentionally maps to the repository's version-root isolation behavior.
    ///
    /// @param settings mutable local instance settings
    /// @param snapshot edited UI state
    private static void applyRunningDirectory(GameSettings.Instance settings, InstanceGameSettingsSnapshot snapshot) {
        if (snapshot.runningDirectoryOverridden()) {
            settings.runningDirectoryProperty().setValue(snapshot.runningDirectory());
            settings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        } else {
            settings.getOverrideProperties().remove(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        }
    }

    /// Returns whether a local instance setting overrides any of the specified property names.
    ///
    /// @param settings local instance settings, or `null` when none have been saved
    /// @param propertyNames property names in one UI setting group
    /// @return whether at least one property name is overridden locally
    private static boolean isAnyOverridden(
            @Nullable GameSettings.Instance settings,
            String... propertyNames) {
        Objects.requireNonNull(propertyNames, "propertyNames");
        for (String propertyName : propertyNames) {
            if (isOverridden(settings, propertyName)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether one property has a local override marker.
    ///
    /// @param settings local instance settings, or `null` when none have been saved
    /// @param propertyName serialized property name
    /// @return whether the property is local to this instance
    private static boolean isOverridden(@Nullable GameSettings.Instance settings, String propertyName) {
        return settings != null && settings.getOverrideProperties().contains(Objects.requireNonNull(propertyName, "propertyName"));
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
