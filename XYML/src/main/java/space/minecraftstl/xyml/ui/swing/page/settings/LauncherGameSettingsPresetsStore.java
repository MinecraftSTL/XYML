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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.property.ObjectProperty;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameSettingsPresets;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Adapts process-wide launcher game-settings presets to the independent Swing preset page.
///
/// The launcher setting objects are intentionally mutated only on the Swing EDT. Their installed `JsonSettingFile`
/// auto-save listeners enqueue persistence through the existing asynchronous `FileSaver`, so this adapter never
/// performs disk I/O or a synchronous overwrite in response to a page command.
@NotNullByDefault
public final class LauncherGameSettingsPresetsStore implements GameSettingsPresetsStore {
    /// Launcher detached preset document observed by this adapter.
    private final GameSettingsPresets presets;

    /// Launcher launcher setting that identifies the default preset.
    private final ObjectProperty<@Nullable GameSettingsPresetID> defaultPreset;

    /// Publishes immutable page snapshots after either launcher source changes.
    private final ValueChangeSupport<GameSettingsPresetsSnapshot> snapshots = new ValueChangeSupport<>(this);

    /// Guards the immutable snapshot and lifecycle flag against a late launcher publication.
    private final Object snapshotLock = new Object();

    /// Subscription to nested preset and list changes.
    private final Subscription presetsSubscription;

    /// Subscription to default-preset selection changes.
    private final Subscription defaultPresetSubscription;

    /// Last immutable state returned to Swing consumers.
    private GameSettingsPresetsSnapshot currentSnapshot;

    /// Whether this adapter has detached from the process-wide launcher settings.
    private boolean closed;

    /// Whether one command is changing multiple observable preset properties.
    private boolean batchingMutation;

    /// Whether a source event was suppressed until the current command finishes.
    private boolean snapshotRefreshPending;

    /// Creates an adapter for the currently loaded process-wide settings model.
    ///
    /// @return adapter over the loaded game-settings preset document
    public static LauncherGameSettingsPresetsStore createForCurrentSettings() {
        return new LauncherGameSettingsPresetsStore(
                SettingsManager.gameSettingsPresets(),
                SettingsManager.settings().defaultGameSettingsPresetProperty());
    }

    /// Creates an adapter around explicit launcher settings sources.
    ///
    /// Package visibility keeps synthetic test sources possible without exposing a second public production entry point.
    ///
    /// @param presets reusable preset document
    /// @param defaultPreset launcher default-preset property
    LauncherGameSettingsPresetsStore(
            GameSettingsPresets presets,
            ObjectProperty<@Nullable GameSettingsPresetID> defaultPreset) {
        this.presets = Objects.requireNonNull(presets, "presets");
        this.defaultPreset = Objects.requireNonNull(defaultPreset, "defaultPreset");
        currentSnapshot = createSnapshot(0L);
        presetsSubscription = presets.changes().subscribe(change -> publishCurrentSnapshot());
        defaultPresetSubscription = defaultPreset.subscribe(change -> publishCurrentSnapshot());
    }

    /// Returns the last immutable state without loading or saving a file.
    ///
    /// @return latest available preset state
    @Override
    public GameSettingsPresetsSnapshot snapshot() {
        synchronized (snapshotLock) {
            return currentSnapshot;
        }
    }

    /// Registers one immutable-snapshot listener.
    ///
    /// @param listener target listener
    /// @return independently removable registration
    @Override
    public Subscription subscribe(ValueChangeListener<GameSettingsPresetsSnapshot> listener) {
        return snapshots.subscribe(Objects.requireNonNull(listener, "listener"));
    }

    /// Creates and selects a new preset, assigning an automatic name when the requested name is blank.
    ///
    /// @param name optional custom name
    /// @return completion with the resulting snapshot
    @Override
    public CompletionStage<GameSettingsPresetsSnapshot> createPreset(String name) {
        return mutate(() -> {
            String normalizedName = normalizeOptionalName(name);
            if (!normalizedName.isEmpty()) {
                requireUniqueName(normalizedName, null);
            }

            GameSettings.Preset preset = new GameSettings.Preset(presets.newPresetId());
            if (normalizedName.isEmpty()) {
                preset.autoNameNumberProperty().setValue(presets.newPresetAutoNameNumber());
            } else {
                preset.nameProperty().setValue(LocalizedText.plain(normalizedName));
            }
            presets.getPresets().add(preset);

            if (presets.getPreset(defaultPreset.get()) == null) {
                defaultPreset.set(preset.idProperty().getValue());
            }
            return snapshot();
        });
    }

    /// Persists a non-blank custom name for one existing preset.
    ///
    /// @param id preset identity
    /// @param name requested custom display name
    /// @return completion with the resulting snapshot
    @Override
    public CompletionStage<GameSettingsPresetsSnapshot> renamePreset(GameSettingsPresetID id, String name) {
        return mutate(() -> {
            GameSettings.Preset preset = requirePreset(id);
            String normalizedName = normalizeRequiredName(name);
            requireUniqueName(normalizedName, preset);
            preset.nameProperty().setValue(LocalizedText.plain(normalizedName));
            preset.autoNameNumberProperty().setValue(null);
            return snapshot();
        });
    }

    /// Removes a preset and retargets the default selection before the removed identity disappears.
    ///
    /// @param id preset identity
    /// @return completion with the resulting snapshot
    @Override
    public CompletionStage<GameSettingsPresetsSnapshot> deletePreset(GameSettingsPresetID id) {
        return mutate(() -> {
            GameSettings.Preset preset = requirePreset(id);
            int index = presets.getPresets().indexOf(preset);
            if (index < 0 || presets.getPresets().size() <= 1) {
                throw new IllegalStateException("At least one game settings preset must remain");
            }

            GameSettings.Preset fallback = presets.getPresets().get(index == 0 ? 1 : index - 1);
            if (id.equals(defaultPreset.get())) {
                defaultPreset.set(fallback.idProperty().getValue());
            }
            presets.getPresets().remove(index);
            if (presets.getPreset(defaultPreset.get()) == null) {
                defaultPreset.set(fallback.idProperty().getValue());
            }
            return snapshot();
        });
    }

    /// Changes the default preset used by newly created instances and invalid parent fallbacks.
    ///
    /// @param id selected preset identity
    /// @return completion with the resulting snapshot
    @Override
    public CompletionStage<GameSettingsPresetsSnapshot> setDefaultPreset(GameSettingsPresetID id) {
        return mutate(() -> {
            GameSettings.Preset preset = requirePreset(id);
            defaultPreset.set(preset.idProperty().getValue());
            return snapshot();
        });
    }

    /// Applies every reusable game-setting field to an existing preset.
    ///
    /// @param editor validated values from the Swing editor
    /// @return completion with the resulting snapshot
    @Override
    public CompletionStage<GameSettingsPresetsSnapshot> updatePreset(GameSettingsPresetEditor editor) {
        GameSettingsPresetEditor values = Objects.requireNonNull(editor, "editor");
        return mutate(() -> {
            GameSettings.Preset preset = requirePreset(values.id());
            preset.autoMemoryProperty().setValue(values.memory().automatic());
            preset.maxMemoryProperty().setValue(values.memory().maximumMiB());

            preset.javaTypeProperty().setValue(values.javaRuntime().type());
            preset.customJavaVersionProperty().setValue(values.javaRuntime().customVersion());
            preset.customJavaPathProperty().setValue(values.javaRuntime().customPath());
            preset.detectedJavaProperty().setValue(values.javaRuntime().detectedJava());

            preset.windowTypeProperty().setValue(values.window().type());
            preset.widthProperty().setValue(values.window().width());
            preset.heightProperty().setValue(values.window().height());

            preset.launcherVisibilityProperty().setValue(values.launcher().visibility());
            preset.allowAutoAgentProperty().setValue(values.launcher().allowAutoAgent());
            preset.disableAutoGameOptionsProperty().setValue(values.launcher().disableAutoGameOptions());
            preset.showLogsProperty().setValue(values.launcher().showLogs());
            preset.enableDebugLogOutputProperty().setValue(values.launcher().debugLog());
            preset.notCheckGameProperty().setValue(values.launcher().notCheckGame());

            preset.quickPlayProperty().setValue(values.quickPlay().type());
            preset.quickPlayMultiplayerProperty().setValue(values.quickPlay().multiplayer());
            preset.quickPlaySingleplayerProperty().setValue(values.quickPlay().singleplayer());
            preset.quickPlayRealmsProperty().setValue(values.quickPlay().realms());

            preset.runningDirectoryProperty().setValue(values.launchOptions().runningDirectory());
            preset.gameArgumentsProperty().setValue(values.launchOptions().gameArguments());
            preset.environmentVariablesProperty().setValue(values.launchOptions().environmentVariables());
            preset.processPriorityProperty().setValue(values.launchOptions().priority());

            preset.noJVMOptionsProperty().setValue(values.jvm().noOptions());
            preset.noOptimizingJVMOptionsProperty().setValue(values.jvm().noOptimizingOptions());
            preset.notCheckJVMProperty().setValue(values.jvm().notCheckJvm());
            preset.jvmOptionsProperty().setValue(values.jvm().options());
            preset.minMemoryProperty().setValue(values.jvm().minimumMemoryMiB());
            preset.permSizeProperty().setValue(values.jvm().permanentGenerationMiB());

            preset.preLaunchCommandProperty().setValue(values.commands().preLaunch());
            preset.commandWrapperProperty().setValue(values.commands().wrapper());
            preset.postExitCommandProperty().setValue(values.commands().postExit());

            preset.graphicsBackendProperty().setValue(values.graphics().backend());
            preset.openGLRendererProperty().setValue(values.graphics().openGlRenderer());
            preset.vulkanRendererProperty().setValue(values.graphics().vulkanRenderer());

            preset.useCustomNativesProperty().setValue(values.nativeLibraries().customDirectoryEnabled());
            preset.nativesDirectoryProperty().setValue(values.nativeLibraries().directory());
            preset.notPatchNativesProperty().setValue(values.nativeLibraries().patchingDisabled());
            preset.useNativeGLFWProperty().setValue(values.nativeLibraries().nativeGlfw());
            preset.useNativeOpenALProperty().setValue(values.nativeLibraries().nativeOpenAl());
            preset.defaultIsolationTypeProperty().setValue(values.defaultIsolationType());
            return snapshot();
        });
    }

    /// Releases raw launcher subscriptions and prevents late callbacks from publishing more snapshots.
    @Override
    public void close() {
        synchronized (snapshotLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        presetsSubscription.unsubscribe();
        defaultPresetSubscription.unsubscribe();
    }

    /// Runs a memory-only mutation on the Swing EDT and exposes failures as an asynchronous command result.
    ///
    /// @param mutation action that changes the launcher model
    /// @return completed or failed snapshot stage
    private CompletionStage<GameSettingsPresetsSnapshot> mutate(
            Supplier<GameSettingsPresetsSnapshot> mutation) {
        try {
            EdtDispatcher.requireEventDispatchThread();
            synchronized (snapshotLock) {
                if (closed) {
                    throw new IllegalStateException("Game settings preset store is closed");
                }
            }
            if (SettingsManager.isGameSettingsReadOnly()) {
                throw new IllegalStateException("Game settings preset file is read-only");
            }
            synchronized (snapshotLock) {
                batchingMutation = true;
                snapshotRefreshPending = false;
            }
            try {
                Objects.requireNonNull(mutation.get(), "mutation result");
            } finally {
                finishMutationBatch();
            }
            return CompletableFuture.completedFuture(snapshot());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Ends one command batch and publishes exactly one complete replacement snapshot when any source changed.
    private void finishMutationBatch() {
        GameSettingsPresetsSnapshot previous;
        GameSettingsPresetsSnapshot next;
        synchronized (snapshotLock) {
            batchingMutation = false;
            if (!snapshotRefreshPending || closed) {
                snapshotRefreshPending = false;
                return;
            }
            snapshotRefreshPending = false;
            previous = currentSnapshot;
            next = createSnapshot(previous.revision() + 1L);
            currentSnapshot = next;
        }
        snapshots.fireChange(previous, next);
    }

    /// Returns one existing preset or fails the requested command without modifying unrelated presets.
    ///
    /// @param id requested preset identity
    /// @return matching live preset
    private GameSettings.Preset requirePreset(GameSettingsPresetID id) {
        @Nullable GameSettings.Preset preset = presets.getPreset(Objects.requireNonNull(id, "id"));
        if (preset == null) {
            throw new IllegalArgumentException("Unknown game settings preset: " + id);
        }
        return preset;
    }

    /// Normalizes an optional name, using an empty string to request automatic naming.
    ///
    /// @param name user-entered name
    /// @return trimmed custom name or an empty automatic-name request
    private static String normalizeOptionalName(String name) {
        return Objects.requireNonNull(name, "name").trim();
    }

    /// Normalizes a required custom name and rejects a blank result.
    ///
    /// @param name user-entered name
    /// @return trimmed non-blank name
    private static String normalizeRequiredName(String name) {
        String normalized = normalizeOptionalName(name);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Preset name must not be blank");
        }
        return normalized;
    }

    /// Rejects a requested display name that is already assigned to another preset.
    ///
    /// @param requestedName normalized candidate name
    /// @param excluded preset being renamed, or null while creating a new preset
    private void requireUniqueName(String requestedName, @Nullable GameSettings.Preset excluded) {
        for (GameSettings.Preset candidate : presets.getPresets()) {
            if (candidate != excluded && requestedName.equals(displayName(candidate))) {
                throw new IllegalArgumentException(i18n("settings.type.global.preset.duplicate_name"));
            }
        }
    }

    /// Publishes a new immutable snapshot after a launcher observable reports a completed mutation.
    private void publishCurrentSnapshot() {
        GameSettingsPresetsSnapshot previous;
        GameSettingsPresetsSnapshot next;
        synchronized (snapshotLock) {
            if (closed) {
                return;
            }
            if (batchingMutation) {
                snapshotRefreshPending = true;
                return;
            }
            previous = currentSnapshot;
            next = createSnapshot(previous.revision() + 1L);
            currentSnapshot = next;
        }
        snapshots.fireChange(previous, next);
    }

    /// Builds one immutable presentation snapshot from the loaded launcher settings objects.
    ///
    /// @param revision revision to place on the resulting snapshot
    /// @return immutable page state
    private GameSettingsPresetsSnapshot createSnapshot(long revision) {
        @Nullable GameSettingsPresetID defaultId = defaultPreset.get();
        List<GameSettingsPresetSnapshot> entries = new ArrayList<>();
        for (GameSettings.Preset preset : presets.getPresets()) {
            entries.add(snapshotOf(preset, defaultId));
        }
        return new GameSettingsPresetsSnapshot(revision, !SettingsManager.isGameSettingsReadOnly(), entries);
    }

    /// Maps one mutable launcher preset to immutable Swing-facing values.
    ///
    /// @param preset source launcher preset
    /// @param defaultId current default identity, or null when none is configured
    /// @return immutable preset representation
    private static GameSettingsPresetSnapshot snapshotOf(
            GameSettings.Preset preset,
            @Nullable GameSettingsPresetID defaultId) {
        GameSettings.Preset source = Objects.requireNonNull(preset, "preset");
        @Nullable LocalizedText localizedName = source.nameProperty().getValue();
        @Nullable String customName = localizedName == null
                ? null
                : localizedName.getText(I18n.getLocale().getCandidateLocales());
        GameSettingsPresetID id = source.idProperty().getValue();
        return new GameSettingsPresetSnapshot(
                id,
                displayName(source),
                customName,
                source.autoNameNumberProperty().getValue(),
                id.equals(defaultId),
                new GameSettingsPresetEditor(
                        id,
                        new GameSettingsPresetEditor.MemorySettings(
                                source.autoMemoryProperty().getValue(),
                                source.maxMemoryProperty().getValue()),
                        new GameSettingsPresetEditor.JavaRuntimeSettings(
                                source.javaTypeProperty().getValue(),
                                source.customJavaVersionProperty().getValue(),
                                source.customJavaPathProperty().getValue(),
                                source.detectedJavaProperty().getValue()),
                        new GameSettingsPresetEditor.WindowSettings(
                                source.windowTypeProperty().getValue(),
                                source.widthProperty().getValue(),
                                source.heightProperty().getValue()),
                        new GameSettingsPresetEditor.LauncherSettings(
                                source.launcherVisibilityProperty().getValue(),
                                source.allowAutoAgentProperty().getValue(),
                                source.disableAutoGameOptionsProperty().getValue(),
                                source.showLogsProperty().getValue(),
                                source.enableDebugLogOutputProperty().getValue(),
                                source.notCheckGameProperty().getValue()),
                        new GameSettingsPresetEditor.QuickPlaySettings(
                                source.quickPlayProperty().getValue(),
                                source.quickPlayMultiplayerProperty().getValue(),
                                source.quickPlaySingleplayerProperty().getValue(),
                                source.quickPlayRealmsProperty().getValue()),
                        new GameSettingsPresetEditor.LaunchOptionsSettings(
                                source.runningDirectoryProperty().getValue(),
                                source.gameArgumentsProperty().getValue(),
                                source.environmentVariablesProperty().getValue(),
                                source.processPriorityProperty().getValue()),
                        new GameSettingsPresetEditor.JvmSettings(
                                source.noJVMOptionsProperty().getValue(),
                                source.noOptimizingJVMOptionsProperty().getValue(),
                                source.notCheckJVMProperty().getValue(),
                                source.jvmOptionsProperty().getValue(),
                                source.minMemoryProperty().getValue(),
                                source.permSizeProperty().getValue()),
                        new GameSettingsPresetEditor.CommandSettings(
                                source.preLaunchCommandProperty().getValue(),
                                source.commandWrapperProperty().getValue(),
                                source.postExitCommandProperty().getValue()),
                        new GameSettingsPresetEditor.GraphicsSettings(
                                source.graphicsBackendProperty().getValue(),
                                source.openGLRendererProperty().getValue(),
                                source.vulkanRendererProperty().getValue()),
                        new GameSettingsPresetEditor.NativeLibrarySettings(
                                source.useCustomNativesProperty().getValue(),
                                source.nativesDirectoryProperty().getValue(),
                                source.notPatchNativesProperty().getValue(),
                                source.useNativeGLFWProperty().getValue(),
                                source.useNativeOpenALProperty().getValue()),
                        source.defaultIsolationTypeProperty().getValue()));
    }

    /// Returns the localized visible name used to compare and render presets.
    ///
    /// @param preset source launcher preset
    /// @return non-blank display name
    private static String displayName(GameSettings.Preset preset) {
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
}
