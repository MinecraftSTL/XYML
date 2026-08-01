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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GraphicsAPI;
import space.minecraftstl.xyml.game.ProcessPriority;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;

import java.util.List;
import java.util.Objects;

/// Immutable editable state for one instance's complete inherited game-settings surface.
///
/// Every setting keeps its own override flag because `GameSettings.Instance.overrideProperties` stores inheritance
/// per serialized property key. Grouping controls visually must never merge those durable flags.
///
/// @param writable whether the backing instance settings file accepts changes
/// @param parentPreset parent global game-settings preset selection
/// @param memory memory-allocation values and override states
/// @param javaRuntime Java selection values and override states
/// @param window game-window mode and resolution values
/// @param launcher launcher behavior and diagnostics values
/// @param quickPlay automatic post-launch destination values
/// @param launchOptions working-directory, game-argument, environment, and priority values
/// @param jvm JVM validation, argument, and legacy memory values
/// @param commands pre-launch, wrapper, and post-exit command values
/// @param graphics graphics API and renderer values
/// @param nativeLibraries native-library replacement values
@NotNullByDefault
public record InstanceGameSettingsSnapshot(
        boolean writable,
        ParentPresetSettings parentPreset,
        MemorySettings memory,
        JavaRuntimeSettings javaRuntime,
        WindowSettings window,
        LauncherSettings launcher,
        QuickPlaySettings quickPlay,
        LaunchOptionsSettings launchOptions,
        JvmSettings jvm,
        CommandSettings commands,
        GraphicsSettings graphics,
        NativeLibrarySettings nativeLibraries) {
    /// Rejects missing setting groups before the snapshot reaches Swing controls or persistence.
    public InstanceGameSettingsSnapshot {
        Objects.requireNonNull(parentPreset, "parentPreset");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(javaRuntime, "javaRuntime");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(launcher, "launcher");
        Objects.requireNonNull(quickPlay, "quickPlay");
        Objects.requireNonNull(launchOptions, "launchOptions");
        Objects.requireNonNull(jvm, "jvm");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(nativeLibraries, "nativeLibraries");
    }

    /// Parent global game-settings preset selection for an instance-specific settings file.
    ///
    /// @param selectedId selected parent preset ID, or `null` for the launcher's default preset
    /// @param choices selectable parent presets, including the default fallback entry
    @NotNullByDefault
    public record ParentPresetSettings(
            @Nullable GameSettingsPresetID selectedId,
            @Unmodifiable List<InstanceGameSettingsParentPreset> choices) {
        /// Defensively copies the available preset choices.
        public ParentPresetSettings {
            choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        }
    }

    /// Memory-allocation values with independent automatic and maximum-memory inheritance.
    ///
    /// @param automaticOverridden whether automatic allocation is local to the instance
    /// @param automatic whether XYML computes the memory allocation automatically
    /// @param maximumOverridden whether maximum heap size is local to the instance
    /// @param maximumMiB requested manual maximum heap in MiB
    @NotNullByDefault
    public record MemorySettings(
            boolean automaticOverridden,
            boolean automatic,
            boolean maximumOverridden,
            int maximumMiB) {
        /// Validates the positive heap-size representation used by the editor.
        public MemorySettings {
            if (maximumMiB <= 0) {
                throw new IllegalArgumentException("maximumMiB must be positive");
            }
        }

        /// Returns whether either memory property is local to the instance.
        ///
        /// @return whether this group contains any local override
        public boolean anyOverridden() {
            return automaticOverridden || maximumOverridden;
        }
    }

    /// Java-selection values with one override flag for every serialized Java property.
    ///
    /// @param typeOverridden whether Java selection strategy is local to the instance
    /// @param type effective Java selection strategy
    /// @param customVersionOverridden whether the requested Java major is local
    /// @param customVersion effective Java major text used by `VERSION`
    /// @param customPathOverridden whether the custom executable path is local
    /// @param customPath effective executable path used by `CUSTOM`
    /// @param detectedJavaOverridden whether the detected runtime reference is local
    /// @param detectedJava effective detected runtime reference used by `DETECTED`
    @NotNullByDefault
    public record JavaRuntimeSettings(
            boolean typeOverridden,
            JavaVersionType type,
            boolean customVersionOverridden,
            String customVersion,
            boolean customPathOverridden,
            String customPath,
            boolean detectedJavaOverridden,
            GameSettings.DetectedJava detectedJava) {
        /// Rejects missing Java values before strategy-specific validation.
        public JavaRuntimeSettings {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(customVersion, "customVersion");
            Objects.requireNonNull(customPath, "customPath");
            Objects.requireNonNull(detectedJava, "detectedJava");
        }

        /// Returns whether any Java-selection property is local to the instance.
        ///
        /// @return whether this group contains any local override
        public boolean anyOverridden() {
            return typeOverridden
                    || customVersionOverridden
                    || customPathOverridden
                    || detectedJavaOverridden;
        }
    }

    /// Game-window mode and optional resolution values.
    ///
    /// @param typeOverridden whether the window mode is local
    /// @param type effective window mode
    /// @param widthOverridden whether the window width is local
    /// @param width effective window width, or zero for game default
    /// @param heightOverridden whether the window height is local
    /// @param height effective window height, or zero for game default
    @NotNullByDefault
    public record WindowSettings(
            boolean typeOverridden,
            GameWindowType type,
            boolean widthOverridden,
            double width,
            boolean heightOverridden,
            double height) {
        /// Validates a finite non-negative resolution representation.
        public WindowSettings {
            Objects.requireNonNull(type, "type");
            if (!Double.isFinite(width) || !Double.isFinite(height) || width < 0.0D || height < 0.0D) {
                throw new IllegalArgumentException("window dimensions must be finite and non-negative");
            }
        }
    }

    /// Launcher visibility, automatic adjustment, and diagnostic behavior values.
    ///
    /// @param visibilityOverridden whether launcher visibility is local
    /// @param visibility effective launcher visibility behavior
    /// @param allowAutoAgentOverridden whether automatic agent injection is local
    /// @param allowAutoAgent whether automatic agent injection is allowed
    /// @param disableAutoGameOptionsOverridden whether automatic game options are local
    /// @param disableAutoGameOptions whether automatic game option adjustment is disabled
    /// @param showLogsOverridden whether log-window visibility is local
    /// @param showLogs whether the launch log window opens
    /// @param debugLogOverridden whether debug logging is local
    /// @param debugLog whether debug log output is enabled
    /// @param notCheckGameOverridden whether game completeness validation is local
    /// @param notCheckGame whether game completeness validation is skipped
    @NotNullByDefault
    public record LauncherSettings(
            boolean visibilityOverridden,
            LauncherVisibility visibility,
            boolean allowAutoAgentOverridden,
            boolean allowAutoAgent,
            boolean disableAutoGameOptionsOverridden,
            boolean disableAutoGameOptions,
            boolean showLogsOverridden,
            boolean showLogs,
            boolean debugLogOverridden,
            boolean debugLog,
            boolean notCheckGameOverridden,
            boolean notCheckGame) {
        /// Rejects a missing launcher visibility value.
        public LauncherSettings {
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    /// Quick Play mode and target values.
    ///
    /// @param typeOverridden whether the Quick Play mode is local
    /// @param type effective Quick Play mode
    /// @param multiplayerOverridden whether the multiplayer server is local
    /// @param multiplayer effective multiplayer server address
    /// @param singleplayerOverridden whether the singleplayer world is local
    /// @param singleplayer effective singleplayer world directory name
    /// @param realmsOverridden whether the Realms target is local
    /// @param realms effective Realms identifier
    @NotNullByDefault
    public record QuickPlaySettings(
            boolean typeOverridden,
            QuickPlayType type,
            boolean multiplayerOverridden,
            String multiplayer,
            boolean singleplayerOverridden,
            String singleplayer,
            boolean realmsOverridden,
            String realms) {
        /// Rejects missing Quick Play values.
        public QuickPlaySettings {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(multiplayer, "multiplayer");
            Objects.requireNonNull(singleplayer, "singleplayer");
            Objects.requireNonNull(realms, "realms");
        }
    }

    /// Advanced launch values outside JVM and command-hook settings.
    ///
    /// @param runningDirectoryOverridden whether the working directory is local
    /// @param runningDirectory effective custom working directory
    /// @param gameArgumentsOverridden whether extra Minecraft arguments are local
    /// @param gameArguments effective extra Minecraft arguments
    /// @param environmentOverridden whether environment variables are local
    /// @param environmentVariables effective environment-variable assignments
    /// @param priorityOverridden whether process priority is local
    /// @param priority effective process priority
    @NotNullByDefault
    public record LaunchOptionsSettings(
            boolean runningDirectoryOverridden,
            String runningDirectory,
            boolean gameArgumentsOverridden,
            String gameArguments,
            boolean environmentOverridden,
            String environmentVariables,
            boolean priorityOverridden,
            ProcessPriority priority) {
        /// Rejects missing launch-option values.
        public LaunchOptionsSettings {
            Objects.requireNonNull(runningDirectory, "runningDirectory");
            Objects.requireNonNull(gameArguments, "gameArguments");
            Objects.requireNonNull(environmentVariables, "environmentVariables");
            Objects.requireNonNull(priority, "priority");
        }
    }

    /// JVM validation, argument, and legacy memory values.
    ///
    /// @param noOptionsOverridden whether generated JVM argument suppression is local
    /// @param noOptions whether all generated JVM arguments are suppressed
    /// @param noOptimizingOptionsOverridden whether optimizing argument suppression is local
    /// @param noOptimizingOptions whether generated optimizing arguments are suppressed
    /// @param notCheckJvmOverridden whether Java compatibility validation is local
    /// @param notCheckJvm whether Java compatibility validation is skipped
    /// @param optionsOverridden whether additional JVM arguments are local
    /// @param options effective additional JVM arguments
    /// @param minimumMemoryOverridden whether legacy minimum heap size is local
    /// @param minimumMemoryMiB effective legacy minimum heap size, or `null` when unspecified
    /// @param permanentGenerationOverridden whether legacy permanent-generation size is local
    /// @param permanentGenerationMiB effective legacy permanent-generation size text
    @NotNullByDefault
    public record JvmSettings(
            boolean noOptionsOverridden,
            boolean noOptions,
            boolean noOptimizingOptionsOverridden,
            boolean noOptimizingOptions,
            boolean notCheckJvmOverridden,
            boolean notCheckJvm,
            boolean optionsOverridden,
            String options,
            boolean minimumMemoryOverridden,
            @Nullable Integer minimumMemoryMiB,
            boolean permanentGenerationOverridden,
            String permanentGenerationMiB) {
        /// Validates strings and the optional non-negative legacy heap value.
        public JvmSettings {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(permanentGenerationMiB, "permanentGenerationMiB");
            if (minimumMemoryMiB != null && minimumMemoryMiB < 0) {
                throw new IllegalArgumentException("minimumMemoryMiB must not be negative");
            }
        }
    }

    /// Custom process command values.
    ///
    /// @param preLaunchOverridden whether the pre-launch command is local
    /// @param preLaunch effective pre-launch command
    /// @param wrapperOverridden whether the command wrapper is local
    /// @param wrapper effective command wrapper
    /// @param postExitOverridden whether the post-exit command is local
    /// @param postExit effective post-exit command
    @NotNullByDefault
    public record CommandSettings(
            boolean preLaunchOverridden,
            String preLaunch,
            boolean wrapperOverridden,
            String wrapper,
            boolean postExitOverridden,
            String postExit) {
        /// Rejects missing custom command values.
        public CommandSettings {
            Objects.requireNonNull(preLaunch, "preLaunch");
            Objects.requireNonNull(wrapper, "wrapper");
            Objects.requireNonNull(postExit, "postExit");
        }
    }

    /// Graphics API and renderer values.
    ///
    /// @param backendOverridden whether the graphics API is local
    /// @param backend effective graphics API
    /// @param openGlRendererOverridden whether the OpenGL renderer is local
    /// @param openGlRenderer effective OpenGL renderer
    /// @param vulkanRendererOverridden whether the Vulkan renderer is local
    /// @param vulkanRenderer effective Vulkan renderer
    @NotNullByDefault
    public record GraphicsSettings(
            boolean backendOverridden,
            GraphicsAPI backend,
            boolean openGlRendererOverridden,
            Renderer openGlRenderer,
            boolean vulkanRendererOverridden,
            Renderer vulkanRenderer) {
        /// Rejects missing graphics values.
        public GraphicsSettings {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(openGlRenderer, "openGlRenderer");
            Objects.requireNonNull(vulkanRenderer, "vulkanRenderer");
        }
    }

    /// Native-library replacement and system-library values.
    ///
    /// @param customDirectoryEnabledOverridden whether custom-native activation is local
    /// @param customDirectoryEnabled whether a custom native directory is used
    /// @param directoryOverridden whether the native directory path is local
    /// @param directory effective native directory path
    /// @param patchingDisabledOverridden whether native patch suppression is local
    /// @param patchingDisabled whether launcher native patching is disabled
    /// @param nativeGlfwOverridden whether system GLFW use is local
    /// @param nativeGlfw whether system GLFW is used
    /// @param nativeOpenAlOverridden whether system OpenAL use is local
    /// @param nativeOpenAl whether system OpenAL is used
    @NotNullByDefault
    public record NativeLibrarySettings(
            boolean customDirectoryEnabledOverridden,
            boolean customDirectoryEnabled,
            boolean directoryOverridden,
            String directory,
            boolean patchingDisabledOverridden,
            boolean patchingDisabled,
            boolean nativeGlfwOverridden,
            boolean nativeGlfw,
            boolean nativeOpenAlOverridden,
            boolean nativeOpenAl) {
        /// Rejects a missing native-library directory value.
        public NativeLibrarySettings {
            Objects.requireNonNull(directory, "directory");
        }
    }
}
