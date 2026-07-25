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
import org.jetbrains.annotations.UnknownNullability;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.property.InheritableProperty;

import java.util.Objects;
import java.util.function.Function;

import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.CommandSettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.GraphicsSettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.JavaRuntimeSettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.JvmSettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.LaunchOptionsSettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.LauncherSettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.MemorySettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.NativeLibrarySettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.QuickPlaySettings;
import static space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceGameSettingsSnapshot.WindowSettings;

/// Maps every inheritable `GameSettings` property to a Swing snapshot without merging override markers.
@NotNullByDefault
final class InstanceGameSettingsMapper {
    /// Utility class; instances carry no state.
    private InstanceGameSettingsMapper() {
    }

    /// Creates a complete effective snapshot while retaining each instance override marker independently.
    ///
    /// @param writable whether the repository can persist the represented settings
    /// @param instance local instance settings, or `null` when the instance has no settings file yet
    /// @param effective resolved parent-preset and instance values
    /// @return complete immutable editor snapshot
    static InstanceGameSettingsSnapshot snapshot(
            boolean writable,
            @Nullable GameSettings.Instance instance,
            GameSettings.Effective effective) {
        Objects.requireNonNull(effective, "effective");
        @Nullable Integer maximumMemory = effective.getInheritable(GameSettings::maxMemoryProperty);

        return new InstanceGameSettingsSnapshot(
                writable,
                new MemorySettings(
                        isOverridden(instance, GameSettings::autoMemoryProperty),
                        effective.getInheritable(GameSettings::autoMemoryProperty),
                        isOverridden(instance, GameSettings::maxMemoryProperty),
                        maximumMemory != null && maximumMemory > 0
                                ? maximumMemory
                                : GameSettings.SUGGESTED_MEMORY),
                new JavaRuntimeSettings(
                        isOverridden(instance, GameSettings::javaTypeProperty),
                        effective.getInheritable(GameSettings::javaTypeProperty),
                        isOverridden(instance, GameSettings::customJavaVersionProperty),
                        effective.getInheritable(GameSettings::customJavaVersionProperty),
                        isOverridden(instance, GameSettings::customJavaPathProperty),
                        effective.getInheritable(GameSettings::customJavaPathProperty),
                        isOverridden(instance, GameSettings::detectedJavaProperty),
                        effective.getInheritable(GameSettings::detectedJavaProperty)),
                new WindowSettings(
                        isOverridden(instance, GameSettings::windowTypeProperty),
                        effective.getInheritable(GameSettings::windowTypeProperty),
                        isOverridden(instance, GameSettings::widthProperty),
                        normalizedDimension(effective.getInheritable(GameSettings::widthProperty)),
                        isOverridden(instance, GameSettings::heightProperty),
                        normalizedDimension(effective.getInheritable(GameSettings::heightProperty))),
                new LauncherSettings(
                        isOverridden(instance, GameSettings::launcherVisibilityProperty),
                        effective.getInheritable(GameSettings::launcherVisibilityProperty),
                        isOverridden(instance, GameSettings::allowAutoAgentProperty),
                        effective.getInheritable(GameSettings::allowAutoAgentProperty),
                        isOverridden(instance, GameSettings::disableAutoGameOptionsProperty),
                        effective.getInheritable(GameSettings::disableAutoGameOptionsProperty),
                        isOverridden(instance, GameSettings::showLogsProperty),
                        effective.getInheritable(GameSettings::showLogsProperty),
                        isOverridden(instance, GameSettings::enableDebugLogOutputProperty),
                        effective.getInheritable(GameSettings::enableDebugLogOutputProperty),
                        isOverridden(instance, GameSettings::notCheckGameProperty),
                        effective.getInheritable(GameSettings::notCheckGameProperty)),
                new QuickPlaySettings(
                        isOverridden(instance, GameSettings::quickPlayProperty),
                        effective.getInheritable(GameSettings::quickPlayProperty),
                        isOverridden(instance, GameSettings::quickPlayMultiplayerProperty),
                        effective.getInheritable(GameSettings::quickPlayMultiplayerProperty),
                        isOverridden(instance, GameSettings::quickPlaySingleplayerProperty),
                        effective.getInheritable(GameSettings::quickPlaySingleplayerProperty),
                        isOverridden(instance, GameSettings::quickPlayRealmsProperty),
                        effective.getInheritable(GameSettings::quickPlayRealmsProperty)),
                new LaunchOptionsSettings(
                        isOverridden(instance, GameSettings::runningDirectoryProperty),
                        effective.getInheritable(GameSettings::runningDirectoryProperty),
                        isOverridden(instance, GameSettings::gameArgumentsProperty),
                        effective.getInheritable(GameSettings::gameArgumentsProperty),
                        isOverridden(instance, GameSettings::environmentVariablesProperty),
                        effective.getInheritable(GameSettings::environmentVariablesProperty),
                        isOverridden(instance, GameSettings::processPriorityProperty),
                        effective.getInheritable(GameSettings::processPriorityProperty)),
                new JvmSettings(
                        isOverridden(instance, GameSettings::noJVMOptionsProperty),
                        effective.getInheritable(GameSettings::noJVMOptionsProperty),
                        isOverridden(instance, GameSettings::noOptimizingJVMOptionsProperty),
                        effective.getInheritable(GameSettings::noOptimizingJVMOptionsProperty),
                        isOverridden(instance, GameSettings::notCheckJVMProperty),
                        effective.getInheritable(GameSettings::notCheckJVMProperty),
                        isOverridden(instance, GameSettings::jvmOptionsProperty),
                        effective.getInheritable(GameSettings::jvmOptionsProperty),
                        isOverridden(instance, GameSettings::minMemoryProperty),
                        effective.getInheritable(GameSettings::minMemoryProperty),
                        isOverridden(instance, GameSettings::permSizeProperty),
                        effective.getInheritable(GameSettings::permSizeProperty)),
                new CommandSettings(
                        isOverridden(instance, GameSettings::preLaunchCommandProperty),
                        effective.getInheritable(GameSettings::preLaunchCommandProperty),
                        isOverridden(instance, GameSettings::commandWrapperProperty),
                        effective.getInheritable(GameSettings::commandWrapperProperty),
                        isOverridden(instance, GameSettings::postExitCommandProperty),
                        effective.getInheritable(GameSettings::postExitCommandProperty)),
                new GraphicsSettings(
                        isOverridden(instance, GameSettings::graphicsBackendProperty),
                        effective.getInheritable(GameSettings::graphicsBackendProperty),
                        isOverridden(instance, GameSettings::openGLRendererProperty),
                        effective.getInheritable(GameSettings::openGLRendererProperty),
                        isOverridden(instance, GameSettings::vulkanRendererProperty),
                        effective.getInheritable(GameSettings::vulkanRendererProperty)),
                new NativeLibrarySettings(
                        isOverridden(instance, GameSettings::useCustomNativesProperty),
                        effective.getInheritable(GameSettings::useCustomNativesProperty),
                        isOverridden(instance, GameSettings::nativesDirectoryProperty),
                        effective.getInheritable(GameSettings::nativesDirectoryProperty),
                        isOverridden(instance, GameSettings::notPatchNativesProperty),
                        effective.getInheritable(GameSettings::notPatchNativesProperty),
                        isOverridden(instance, GameSettings::useNativeGLFWProperty),
                        effective.getInheritable(GameSettings::useNativeGLFWProperty),
                        isOverridden(instance, GameSettings::useNativeOpenALProperty),
                        effective.getInheritable(GameSettings::useNativeOpenALProperty)));
    }

    /// Applies all snapshot values and override markers to mutable instance settings.
    ///
    /// Values are written only for local properties. Inherited properties keep their dormant direct value so toggling
    /// inheritance later can restore the user's previous local choice.
    ///
    /// @param settings mutable instance settings
    /// @param snapshot complete edited snapshot
    static void apply(GameSettings.Instance settings, InstanceGameSettingsSnapshot snapshot) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.javaRuntime().typeOverridden()
                && snapshot.javaRuntime().type() == space.minecraftstl.xyml.setting.JavaVersionType.DETECTED
                && snapshot.javaRuntime().detectedJava().isEmpty()) {
            throw new IllegalArgumentException("No detected Java runtime is configured for this instance");
        }

        applyMemory(settings, snapshot.memory());
        applyJava(settings, snapshot.javaRuntime());
        applyWindow(settings, snapshot.window());
        applyLauncher(settings, snapshot.launcher());
        applyQuickPlay(settings, snapshot.quickPlay());
        applyLaunchOptions(settings, snapshot.launchOptions());
        applyJvm(settings, snapshot.jvm());
        applyCommands(settings, snapshot.commands());
        applyGraphics(settings, snapshot.graphics());
        applyNativeLibraries(settings, snapshot.nativeLibraries());
    }

    /// Applies memory allocation properties.
    private static void applyMemory(GameSettings.Instance settings, MemorySettings values) {
        apply(settings, values.automaticOverridden(), values.automatic(), GameSettings::autoMemoryProperty);
        apply(settings, values.maximumOverridden(), values.maximumMiB(), GameSettings::maxMemoryProperty);
    }

    /// Applies Java selection properties.
    private static void applyJava(GameSettings.Instance settings, JavaRuntimeSettings values) {
        apply(settings, values.typeOverridden(), values.type(), GameSettings::javaTypeProperty);
        apply(
                settings,
                values.customVersionOverridden(),
                values.customVersion(),
                GameSettings::customJavaVersionProperty);
        apply(settings, values.customPathOverridden(), values.customPath(), GameSettings::customJavaPathProperty);
        apply(
                settings,
                values.detectedJavaOverridden(),
                values.detectedJava(),
                GameSettings::detectedJavaProperty);
    }

    /// Applies game-window properties.
    private static void applyWindow(GameSettings.Instance settings, WindowSettings values) {
        apply(settings, values.typeOverridden(), values.type(), GameSettings::windowTypeProperty);
        apply(settings, values.widthOverridden(), values.width(), GameSettings::widthProperty);
        apply(settings, values.heightOverridden(), values.height(), GameSettings::heightProperty);
    }

    /// Applies launcher behavior and diagnostics properties.
    private static void applyLauncher(GameSettings.Instance settings, LauncherSettings values) {
        apply(settings, values.visibilityOverridden(), values.visibility(), GameSettings::launcherVisibilityProperty);
        apply(
                settings,
                values.allowAutoAgentOverridden(),
                values.allowAutoAgent(),
                GameSettings::allowAutoAgentProperty);
        apply(
                settings,
                values.disableAutoGameOptionsOverridden(),
                values.disableAutoGameOptions(),
                GameSettings::disableAutoGameOptionsProperty);
        apply(settings, values.showLogsOverridden(), values.showLogs(), GameSettings::showLogsProperty);
        apply(
                settings,
                values.debugLogOverridden(),
                values.debugLog(),
                GameSettings::enableDebugLogOutputProperty);
        apply(
                settings,
                values.notCheckGameOverridden(),
                values.notCheckGame(),
                GameSettings::notCheckGameProperty);
    }

    /// Applies Quick Play mode and target properties.
    private static void applyQuickPlay(GameSettings.Instance settings, QuickPlaySettings values) {
        apply(settings, values.typeOverridden(), values.type(), GameSettings::quickPlayProperty);
        apply(
                settings,
                values.multiplayerOverridden(),
                values.multiplayer(),
                GameSettings::quickPlayMultiplayerProperty);
        apply(
                settings,
                values.singleplayerOverridden(),
                values.singleplayer(),
                GameSettings::quickPlaySingleplayerProperty);
        apply(settings, values.realmsOverridden(), values.realms(), GameSettings::quickPlayRealmsProperty);
    }

    /// Applies working-directory and general launch option properties.
    private static void applyLaunchOptions(GameSettings.Instance settings, LaunchOptionsSettings values) {
        apply(
                settings,
                values.runningDirectoryOverridden(),
                values.runningDirectory(),
                GameSettings::runningDirectoryProperty);
        apply(
                settings,
                values.gameArgumentsOverridden(),
                values.gameArguments(),
                GameSettings::gameArgumentsProperty);
        apply(
                settings,
                values.environmentOverridden(),
                values.environmentVariables(),
                GameSettings::environmentVariablesProperty);
        apply(settings, values.priorityOverridden(), values.priority(), GameSettings::processPriorityProperty);
    }

    /// Applies JVM validation, argument, and legacy memory properties.
    private static void applyJvm(GameSettings.Instance settings, JvmSettings values) {
        apply(settings, values.noOptionsOverridden(), values.noOptions(), GameSettings::noJVMOptionsProperty);
        apply(
                settings,
                values.noOptimizingOptionsOverridden(),
                values.noOptimizingOptions(),
                GameSettings::noOptimizingJVMOptionsProperty);
        apply(
                settings,
                values.notCheckJvmOverridden(),
                values.notCheckJvm(),
                GameSettings::notCheckJVMProperty);
        apply(settings, values.optionsOverridden(), values.options(), GameSettings::jvmOptionsProperty);
        apply(
                settings,
                values.minimumMemoryOverridden(),
                values.minimumMemoryMiB(),
                GameSettings::minMemoryProperty);
        apply(
                settings,
                values.permanentGenerationOverridden(),
                values.permanentGenerationMiB(),
                GameSettings::permSizeProperty);
    }

    /// Applies custom command-hook properties.
    private static void applyCommands(GameSettings.Instance settings, CommandSettings values) {
        apply(settings, values.preLaunchOverridden(), values.preLaunch(), GameSettings::preLaunchCommandProperty);
        apply(settings, values.wrapperOverridden(), values.wrapper(), GameSettings::commandWrapperProperty);
        apply(settings, values.postExitOverridden(), values.postExit(), GameSettings::postExitCommandProperty);
    }

    /// Applies graphics API and renderer properties.
    private static void applyGraphics(GameSettings.Instance settings, GraphicsSettings values) {
        apply(settings, values.backendOverridden(), values.backend(), GameSettings::graphicsBackendProperty);
        apply(
                settings,
                values.openGlRendererOverridden(),
                values.openGlRenderer(),
                GameSettings::openGLRendererProperty);
        apply(
                settings,
                values.vulkanRendererOverridden(),
                values.vulkanRenderer(),
                GameSettings::vulkanRendererProperty);
    }

    /// Applies native-library replacement properties.
    private static void applyNativeLibraries(GameSettings.Instance settings, NativeLibrarySettings values) {
        apply(
                settings,
                values.customDirectoryEnabledOverridden(),
                values.customDirectoryEnabled(),
                GameSettings::useCustomNativesProperty);
        apply(settings, values.directoryOverridden(), values.directory(), GameSettings::nativesDirectoryProperty);
        apply(
                settings,
                values.patchingDisabledOverridden(),
                values.patchingDisabled(),
                GameSettings::notPatchNativesProperty);
        apply(
                settings,
                values.nativeGlfwOverridden(),
                values.nativeGlfw(),
                GameSettings::useNativeGLFWProperty);
        apply(
                settings,
                values.nativeOpenAlOverridden(),
                values.nativeOpenAl(),
                GameSettings::useNativeOpenALProperty);
    }

    /// Writes or removes one local property without disturbing its dormant direct value when inherited.
    ///
    /// @param settings mutable instance settings
    /// @param overridden whether the property should be local
    /// @param value effective edited value
    /// @param propertyGetter property accessor
    /// @param <T> property value type
    private static <T extends @UnknownNullability Object> void apply(
            GameSettings.Instance settings,
            boolean overridden,
            T value,
            Function<GameSettings, InheritableProperty<T>> propertyGetter) {
        InheritableProperty<T> property = propertyGetter.apply(settings);
        if (overridden) {
            property.setValue(value);
            settings.getOverrideProperties().add(property.getName());
        } else {
            settings.getOverrideProperties().remove(property.getName());
        }
    }

    /// Returns whether one property is explicitly local to an instance.
    ///
    /// @param instance local settings, or `null` when none exist
    /// @param propertyGetter property accessor
    /// @param <T> property value type
    /// @return whether the property's serialized name is present in `overrideProperties`
    private static <T extends @UnknownNullability Object> boolean isOverridden(
            @Nullable GameSettings.Instance instance,
            Function<GameSettings, InheritableProperty<T>> propertyGetter) {
        return instance != null
                && instance.getOverrideProperties().contains(propertyGetter.apply(instance).getName());
    }

    /// Normalizes an invalid model dimension without narrowing valid persisted precision or range.
    ///
    /// @param value effective model dimension
    /// @return finite non-negative dimension
    private static double normalizedDimension(double value) {
        return Double.isFinite(value) && value >= 0.0D ? value : 0.0D;
    }
}
