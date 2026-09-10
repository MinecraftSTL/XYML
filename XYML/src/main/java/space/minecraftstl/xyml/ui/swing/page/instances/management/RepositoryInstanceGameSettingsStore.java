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
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

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

    /// Returns the instance root when an installed modpack requires an isolated working directory.
    ///
    /// @return absolute instance-root text, or `null` for configurable instances
    @Override
    public @Nullable String forcedRunningDirectory() {
        return repository.isModpack(instanceId)
                ? repository.getInstanceRoot(instanceId).toAbsolutePath().normalize().toString()
                : null;
    }

    /// Reads all effective values together with each property's independent local inheritance state.
    ///
    /// @return current complete settings snapshot
    @Override
    public InstanceGameSettingsSnapshot snapshot() {
        @Nullable GameSettings.Instance instance = repository.getInstanceGameSettings(instanceId);
        return InstanceGameSettingsMapper.snapshot(
                repository.hasInstance(instanceId) && !repository.isInstanceGameSettingsReadOnly(instanceId),
                parentPresetSettings(instance),
                instance,
                repository.getEffectiveGameSettings(instanceId));
    }

    /// Resolves unsaved local values against the candidate parent preset without mutating repository state.
    ///
    /// @param candidate complete unsaved editor state
    /// @return effective preview retaining every candidate override
    @Override
    public InstanceGameSettingsSnapshot preview(InstanceGameSettingsSnapshot candidate) {
        InstanceGameSettingsSnapshot checkedCandidate = Objects.requireNonNull(candidate, "candidate");
        GameSettings.Instance previewInstance = new GameSettings.Instance();
        InstanceGameSettingsMapper.apply(previewInstance, checkedCandidate);
        GameSettings.Effective effective = GameSettings.resolve(
                repository.getParentGameSettings(previewInstance),
                previewInstance);
        return InstanceGameSettingsMapper.snapshot(
                checkedCandidate.writable(),
                new InstanceGameSettingsSnapshot.ParentPresetSettings(
                        checkedCandidate.parentPreset().selectedId(),
                        parentPresetChoices()),
                previewInstance,
                effective);
    }

    /// Persists a complete snapshot without merging unrelated override markers.
    ///
    /// @param snapshot validated values and inheritance choices to persist
    /// @throws IllegalStateException when this instance is unknown or its settings file is read-only
    @Override
    public void save(InstanceGameSettingsSnapshot snapshot) {
        applySnapshot(Objects.requireNonNull(snapshot, "snapshot"));
        repository.saveGameSettings(instanceId);
    }

    /// Creates the resource-aware task that applies and synchronously persists a complete snapshot.
    ///
    /// The task waits for legacy observable-setting writes queued before its body completes, so the resource lease is
    /// not released while an older asynchronous write can still touch the same configuration file.
    ///
    /// @param snapshot validated values and inheritance choices to persist
    /// @param executor executor used for repository and file work
    /// @return unstarted instance-scoped persistence task
    @Override
    public Task<@Nullable Void> saveTask(InstanceGameSettingsSnapshot snapshot, Executor executor) {
        InstanceGameSettingsSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        Executor checkedExecutor = Objects.requireNonNull(executor, "executor");
        return Task.runAsync("Save instance game settings", checkedExecutor, () -> {
            applySnapshot(checkedSnapshot);
            repository.saveGameSettingsSync(instanceId);
            FileSaver.waitForAllSaves();
        }).setResources(
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.configuration(repository.getInstanceGameSettingsFile(instanceId)));
    }

    /// Applies one validated snapshot to the repository-owned observable settings object.
    ///
    /// @param snapshot values and inheritance choices to apply
    /// @throws IllegalStateException when this instance is unknown or its settings file is read-only
    private void applySnapshot(InstanceGameSettingsSnapshot snapshot) {
        if (!snapshot.writable() || repository.isInstanceGameSettingsReadOnly(instanceId)) {
            throw new IllegalStateException("Instance game settings are read-only");
        }
        @Nullable GameSettings.Instance settings = repository.getInstanceGameSettingsOrCreate(instanceId);
        if (settings == null) {
            throw new IllegalStateException("Instance game settings are unavailable");
        }

        InstanceGameSettingsMapper.apply(settings, snapshot);
    }

    /// Returns whether the represented instance has a loaded settings object that can be recovered.
    ///
    /// @return whether backup-and-overwrite recovery is available
    @Override
    public boolean canForceOverwrite() {
        return repository.hasInstance(instanceId) && repository.getInstanceGameSettings(instanceId) != null;
    }

    /// Backs up and overwrites the represented instance settings through the repository recovery API.
    @Override
    public void forceOverwrite() {
        if (!canForceOverwrite()) {
            throw new IllegalStateException("Instance game settings are unavailable");
        }
        repository.forceOverwriteInstanceGameSettings(instanceId);
    }

    /// Creates the resource-aware task that backs up and synchronously overwrites a read-only settings file.
    ///
    /// @param executor executor used for repository and file work
    /// @return unstarted instance-scoped recovery task
    @Override
    public Task<@Nullable Void> forceOverwriteTask(Executor executor) {
        Executor checkedExecutor = Objects.requireNonNull(executor, "executor");
        return Task.runAsync("Force overwrite instance game settings", checkedExecutor, () -> {
            if (!canForceOverwrite()) {
                throw new IllegalStateException("Instance game settings are unavailable");
            }
            repository.forceOverwriteInstanceGameSettingsSync(instanceId);
            FileSaver.waitForAllSaves();
        }).setResources(
                TaskResource.gameInstance(repository.getInstanceRoot(instanceId)),
                TaskResource.configuration(repository.getInstanceGameSettingsFile(instanceId)));
    }

    /// Builds the durable parent-preset state for one loaded instance settings object.
    ///
    /// @param instance loaded instance settings, or `null` when no local file exists
    /// @return selected ID and localized available choices
    private static InstanceGameSettingsSnapshot.ParentPresetSettings parentPresetSettings(
            @Nullable GameSettings.Instance instance) {
        return new InstanceGameSettingsSnapshot.ParentPresetSettings(
                instance == null ? null : instance.parentProperty().getValue(),
                parentPresetChoices());
    }

    /// Builds the default fallback followed by all currently configured global presets.
    ///
    /// @return immutable localized preset choices
    private static List<InstanceGameSettingsParentPreset> parentPresetChoices() {
        List<InstanceGameSettingsParentPreset> choices = new ArrayList<>();
        choices.add(new InstanceGameSettingsParentPreset(
                null,
                i18n("settings.type.global.preset.default")));
        for (GameSettings.Preset preset : SettingsManager.gameSettingsPresets().getPresets()) {
            choices.add(new InstanceGameSettingsParentPreset(
                    preset.idProperty().getValue(),
                    presetDisplayName(preset)));
        }
        return List.copyOf(choices);
    }

    /// Returns the localized visible name of one configured global preset.
    ///
    /// @param preset source preset
    /// @return non-blank display name
    private static String presetDisplayName(GameSettings.Preset preset) {
        @Nullable LocalizedText localizedName = preset.nameProperty().getValue();
        @Nullable String customName = localizedName == null
                ? null
                : localizedName.getText(I18n.getLocale().getCandidateLocales());
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        @Nullable Integer autoNameNumber = preset.autoNameNumberProperty().getValue();
        return autoNameNumber == null
                ? preset.idProperty().getValue().toString()
                : i18n("settings.type.global.preset.auto_name", autoNameNumber);
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
