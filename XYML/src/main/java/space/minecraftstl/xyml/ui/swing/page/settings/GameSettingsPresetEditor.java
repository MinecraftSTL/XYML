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
import space.minecraftstl.xyml.game.GraphicsAPI;
import space.minecraftstl.xyml.game.ProcessPriority;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.setting.DefaultIsolationType;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot;

import java.util.Objects;

/// Contains every directly persisted field of one reusable game-settings preset.
///
/// The grouped values deliberately mirror the complete inherited settings surface used by an instance. A global
/// preset has no override flags, so conversion to the shared editor marks every field as direct.
///
/// @param id stable identity of the preset to update
/// @param memory memory-allocation values
/// @param javaRuntime Java runtime selection values
/// @param window game-window values
/// @param launcher launcher behavior and diagnostic values
/// @param quickPlay Quick Play values
/// @param launchOptions general process launch values
/// @param jvm JVM validation, argument, and legacy memory values
/// @param commands process command-hook values
/// @param graphics graphics API and renderer values
/// @param nativeLibraries native-library replacement values
/// @param defaultIsolationType default strategy for isolating newly installed instances
@NotNullByDefault
public record GameSettingsPresetEditor(
        GameSettingsPresetID id,
        MemorySettings memory,
        JavaRuntimeSettings javaRuntime,
        WindowSettings window,
        LauncherSettings launcher,
        QuickPlaySettings quickPlay,
        LaunchOptionsSettings launchOptions,
        JvmSettings jvm,
        CommandSettings commands,
        GraphicsSettings graphics,
        NativeLibrarySettings nativeLibraries,
        DefaultIsolationType defaultIsolationType) {
    /// Rejects missing setting groups before values reach the persistence adapter.
    public GameSettingsPresetEditor {
        Objects.requireNonNull(id, "id");
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
        Objects.requireNonNull(defaultIsolationType, "defaultIsolationType");
    }

    /// Converts direct preset values to the complete shared settings editor surface.
    ///
    /// @param writable whether controls may be edited
    /// @return complete direct-value snapshot with every override marker enabled
    public InstanceGameSettingsSnapshot toEditorSnapshot(boolean writable) {
        return new InstanceGameSettingsSnapshot(
                writable,
                new InstanceGameSettingsSnapshot.MemorySettings(
                        true,
                        memory.automatic(),
                        true,
                        editableMaximumMemory(memory.maximumMiB())),
                new InstanceGameSettingsSnapshot.JavaRuntimeSettings(
                        true,
                        javaRuntime.type(),
                        true,
                        javaRuntime.customVersion(),
                        true,
                        javaRuntime.customPath(),
                        true,
                        javaRuntime.detectedJava()),
                new InstanceGameSettingsSnapshot.WindowSettings(
                        true,
                        window.type(),
                        true,
                        editableDimension(window.width()),
                        true,
                        editableDimension(window.height())),
                new InstanceGameSettingsSnapshot.LauncherSettings(
                        true,
                        launcher.visibility(),
                        true,
                        launcher.allowAutoAgent(),
                        true,
                        launcher.disableAutoGameOptions(),
                        true,
                        launcher.showLogs(),
                        true,
                        launcher.debugLog(),
                        true,
                        launcher.notCheckGame()),
                new InstanceGameSettingsSnapshot.QuickPlaySettings(
                        true,
                        quickPlay.type(),
                        true,
                        quickPlay.multiplayer(),
                        true,
                        quickPlay.singleplayer(),
                        true,
                        quickPlay.realms()),
                new InstanceGameSettingsSnapshot.LaunchOptionsSettings(
                        true,
                        launchOptions.runningDirectory(),
                        true,
                        launchOptions.gameArguments(),
                        true,
                        launchOptions.environmentVariables(),
                        true,
                        launchOptions.priority()),
                new InstanceGameSettingsSnapshot.JvmSettings(
                        true,
                        jvm.noOptions(),
                        true,
                        jvm.noOptimizingOptions(),
                        true,
                        jvm.notCheckJvm(),
                        true,
                        jvm.options(),
                        true,
                        editableMinimumMemory(jvm.minimumMemoryMiB()),
                        true,
                        jvm.permanentGenerationMiB()),
                new InstanceGameSettingsSnapshot.CommandSettings(
                        true,
                        commands.preLaunch(),
                        true,
                        commands.wrapper(),
                        true,
                        commands.postExit()),
                new InstanceGameSettingsSnapshot.GraphicsSettings(
                        true,
                        graphics.backend(),
                        true,
                        graphics.openGlRenderer(),
                        true,
                        graphics.vulkanRenderer()),
                new InstanceGameSettingsSnapshot.NativeLibrarySettings(
                        true,
                        nativeLibraries.customDirectoryEnabled(),
                        true,
                        nativeLibraries.directory(),
                        true,
                        nativeLibraries.patchingDisabled(),
                        true,
                        nativeLibraries.nativeGlfw(),
                        true,
                        nativeLibraries.nativeOpenAl()));
    }

    /// Converts the complete shared settings editor surface back to direct preset values.
    ///
    /// Values that the launcher historically tolerates but the editor cannot represent are normalized for display
    /// and preserved when the normalized field remains unchanged.
    ///
    /// @param original original direct preset values
    /// @param defaultIsolationType selected default isolation strategy
    /// @param snapshot validated complete editor values
    /// @return complete preset update command
    public static GameSettingsPresetEditor fromEditorSnapshot(
            GameSettingsPresetEditor original,
            DefaultIsolationType defaultIsolationType,
            InstanceGameSettingsSnapshot snapshot) {
        GameSettingsPresetEditor source = Objects.requireNonNull(original, "original");
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable Integer originalMaximumMemory = source.memory().maximumMiB();
        @Nullable Integer maximumMemory;
        if (snapshot.memory().maximumMiB() == editableMaximumMemory(originalMaximumMemory)) {
            maximumMemory = originalMaximumMemory;
        } else {
            maximumMemory = snapshot.memory().maximumMiB();
        }
        double width = Double.compare(
                snapshot.window().width(),
                editableDimension(source.window().width())) == 0
                ? source.window().width()
                : snapshot.window().width();
        double height = Double.compare(
                snapshot.window().height(),
                editableDimension(source.window().height())) == 0
                ? source.window().height()
                : snapshot.window().height();
        @Nullable Integer originalMinimumMemory = source.jvm().minimumMemoryMiB();
        @Nullable Integer minimumMemory = Objects.equals(
                snapshot.jvm().minimumMemoryMiB(),
                editableMinimumMemory(originalMinimumMemory))
                ? originalMinimumMemory
                : snapshot.jvm().minimumMemoryMiB();
        return new GameSettingsPresetEditor(
                source.id(),
                new MemorySettings(snapshot.memory().automatic(), maximumMemory),
                new JavaRuntimeSettings(
                        snapshot.javaRuntime().type(),
                        snapshot.javaRuntime().customVersion(),
                        snapshot.javaRuntime().customPath(),
                        snapshot.javaRuntime().detectedJava()),
                new WindowSettings(snapshot.window().type(), width, height),
                new LauncherSettings(
                        snapshot.launcher().visibility(),
                        snapshot.launcher().allowAutoAgent(),
                        snapshot.launcher().disableAutoGameOptions(),
                        snapshot.launcher().showLogs(),
                        snapshot.launcher().debugLog(),
                        snapshot.launcher().notCheckGame()),
                new QuickPlaySettings(
                        snapshot.quickPlay().type(),
                        snapshot.quickPlay().multiplayer(),
                        snapshot.quickPlay().singleplayer(),
                        snapshot.quickPlay().realms()),
                new LaunchOptionsSettings(
                        snapshot.launchOptions().runningDirectory(),
                        snapshot.launchOptions().gameArguments(),
                        snapshot.launchOptions().environmentVariables(),
                        snapshot.launchOptions().priority()),
                new JvmSettings(
                        snapshot.jvm().noOptions(),
                        snapshot.jvm().noOptimizingOptions(),
                        snapshot.jvm().notCheckJvm(),
                        snapshot.jvm().options(),
                        minimumMemory,
                        snapshot.jvm().permanentGenerationMiB()),
                new CommandSettings(
                        snapshot.commands().preLaunch(),
                        snapshot.commands().wrapper(),
                        snapshot.commands().postExit()),
                new GraphicsSettings(
                        snapshot.graphics().backend(),
                        snapshot.graphics().openGlRenderer(),
                        snapshot.graphics().vulkanRenderer()),
                new NativeLibrarySettings(
                        snapshot.nativeLibraries().customDirectoryEnabled(),
                        snapshot.nativeLibraries().directory(),
                        snapshot.nativeLibraries().patchingDisabled(),
                        snapshot.nativeLibraries().nativeGlfw(),
                        snapshot.nativeLibraries().nativeOpenAl()),
                defaultIsolationType);
    }

    /// Converts a nullable or non-positive persisted maximum into the launcher's editable effective value.
    ///
    /// @param value raw persisted maximum
    /// @return positive maximum shown by the editor
    private static int editableMaximumMemory(@Nullable Integer value) {
        return value != null && value > 0 ? value : GameSettings.SUGGESTED_MEMORY;
    }

    /// Converts a historical invalid dimension into the same non-negative value used at launch time.
    ///
    /// @param value raw persisted dimension
    /// @return finite non-negative editor value
    private static double editableDimension(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    /// Converts a historical negative minimum heap value into an empty optional editor value.
    ///
    /// @param value raw persisted minimum
    /// @return non-negative editor value, or `null`
    private static @Nullable Integer editableMinimumMemory(@Nullable Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    /// Memory-allocation values.
    ///
    /// @param automatic whether XYML computes allocation automatically
    /// @param maximumMiB optional maximum heap size in MiB
    @NotNullByDefault
    public record MemorySettings(boolean automatic, @Nullable Integer maximumMiB) {
        /// Retains raw persisted values so legacy dynamic-default sentinels can survive unrelated edits.
        public MemorySettings {
        }
    }

    /// Java runtime selection values.
    ///
    /// @param type Java selection strategy
    /// @param customVersion requested Java major used by `VERSION`
    /// @param customPath executable path used by `CUSTOM`
    /// @param detectedJava runtime identity used by `DETECTED`
    @NotNullByDefault
    public record JavaRuntimeSettings(
            JavaVersionType type,
            String customVersion,
            String customPath,
            GameSettings.DetectedJava detectedJava) {
        /// Rejects missing Java selection values.
        public JavaRuntimeSettings {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(customVersion, "customVersion");
            Objects.requireNonNull(customPath, "customPath");
            Objects.requireNonNull(detectedJava, "detectedJava");
        }
    }

    /// Game-window mode and resolution values.
    ///
    /// @param type window mode
    /// @param width requested width, or zero for game default
    /// @param height requested height, or zero for game default
    @NotNullByDefault
    public record WindowSettings(GameWindowType type, double width, double height) {
        /// Retains raw dimensions while rejecting a missing mode.
        public WindowSettings {
            Objects.requireNonNull(type, "type");
        }
    }

    /// Launcher behavior and diagnostic values.
    ///
    /// @param visibility launcher behavior after game start
    /// @param allowAutoAgent whether automatic Java-agent injection is allowed
    /// @param disableAutoGameOptions whether automatic game-option adjustment is disabled
    /// @param showLogs whether the game log window opens on launch
    /// @param debugLog whether debug log output is enabled
    /// @param notCheckGame whether game completeness validation is skipped
    @NotNullByDefault
    public record LauncherSettings(
            LauncherVisibility visibility,
            boolean allowAutoAgent,
            boolean disableAutoGameOptions,
            boolean showLogs,
            boolean debugLog,
            boolean notCheckGame) {
        /// Rejects a missing launcher visibility value.
        public LauncherSettings {
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    /// Quick Play mode and targets.
    ///
    /// @param type Quick Play destination type
    /// @param multiplayer multiplayer server address
    /// @param singleplayer singleplayer world directory name
    /// @param realms Realms target identifier
    @NotNullByDefault
    public record QuickPlaySettings(
            QuickPlayType type,
            String multiplayer,
            String singleplayer,
            String realms) {
        /// Rejects missing Quick Play values.
        public QuickPlaySettings {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(multiplayer, "multiplayer");
            Objects.requireNonNull(singleplayer, "singleplayer");
            Objects.requireNonNull(realms, "realms");
        }
    }

    /// General process launch values.
    ///
    /// @param runningDirectory custom game working directory
    /// @param gameArguments additional Minecraft arguments
    /// @param environmentVariables environment-variable assignments
    /// @param priority game process priority
    @NotNullByDefault
    public record LaunchOptionsSettings(
            String runningDirectory,
            String gameArguments,
            String environmentVariables,
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
    /// @param noOptions whether generated JVM arguments are disabled
    /// @param noOptimizingOptions whether generated optimization arguments are disabled
    /// @param notCheckJvm whether Java compatibility validation is skipped
    /// @param options additional JVM arguments
    /// @param minimumMemoryMiB optional legacy minimum heap size
    /// @param permanentGenerationMiB legacy permanent-generation size text
    @NotNullByDefault
    public record JvmSettings(
            boolean noOptions,
            boolean noOptimizingOptions,
            boolean notCheckJvm,
            String options,
            @Nullable Integer minimumMemoryMiB,
            String permanentGenerationMiB) {
        /// Rejects missing JVM text values while retaining historical optional minimum sentinels.
        public JvmSettings {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(permanentGenerationMiB, "permanentGenerationMiB");
        }
    }

    /// Process command-hook values.
    ///
    /// @param preLaunch command run before launch
    /// @param wrapper wrapper prepended to the launch command
    /// @param postExit command run after game exit
    @NotNullByDefault
    public record CommandSettings(String preLaunch, String wrapper, String postExit) {
        /// Rejects missing command values.
        public CommandSettings {
            Objects.requireNonNull(preLaunch, "preLaunch");
            Objects.requireNonNull(wrapper, "wrapper");
            Objects.requireNonNull(postExit, "postExit");
        }
    }

    /// Graphics API and renderer values.
    ///
    /// @param backend selected graphics API
    /// @param openGlRenderer selected OpenGL renderer
    /// @param vulkanRenderer selected Vulkan renderer
    @NotNullByDefault
    public record GraphicsSettings(
            GraphicsAPI backend,
            Renderer openGlRenderer,
            Renderer vulkanRenderer) {
        /// Rejects missing graphics values.
        public GraphicsSettings {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(openGlRenderer, "openGlRenderer");
            Objects.requireNonNull(vulkanRenderer, "vulkanRenderer");
        }
    }

    /// Native-library replacement values.
    ///
    /// @param customDirectoryEnabled whether a custom native directory is used
    /// @param directory custom native directory
    /// @param patchingDisabled whether launcher native patching is disabled
    /// @param nativeGlfw whether system GLFW is used
    /// @param nativeOpenAl whether system OpenAL is used
    @NotNullByDefault
    public record NativeLibrarySettings(
            boolean customDirectoryEnabled,
            String directory,
            boolean patchingDisabled,
            boolean nativeGlfw,
            boolean nativeOpenAl) {
        /// Rejects a missing native directory value.
        public NativeLibrarySettings {
            Objects.requireNonNull(directory, "directory");
        }
    }
}
