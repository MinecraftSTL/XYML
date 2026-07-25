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
import space.minecraftstl.xyml.setting.JavaVersionType;

import java.util.Objects;

/// Immutable, editable subset of one instance's launch settings.
///
/// The four override flags mirror `GameSettings.Instance.overrideProperties`: they let the view show an effective
/// inherited value while preserving the user's choice to keep inheriting it from the selected game-settings preset.
///
/// @param writable whether the backing instance settings file accepts changes
/// @param memoryOverridden whether automatic and maximum-memory values are local to this instance
/// @param automaticMemory whether XYML computes the memory allocation automatically
/// @param maximumMemoryMiB requested manual maximum heap in MiB
/// @param javaOverridden whether the Java strategy and its local inputs are instance-specific
/// @param javaVersionType Java selection strategy used when this instance overrides the preset
/// @param customJavaVersion requested Java major version for `VERSION` strategy
/// @param customJavaPath requested Java executable path for `CUSTOM` strategy
/// @param detectedJavaAvailable whether `DETECTED` currently has a persisted runtime reference to use
/// @param jvmOptionsOverridden whether JVM arguments are instance-specific
/// @param jvmOptions free-form JVM arguments passed after XYML's generated arguments
/// @param runningDirectoryOverridden whether the game working directory is instance-specific
/// @param runningDirectory custom working directory, or empty to use the instance version root
@NotNullByDefault
public record InstanceGameSettingsSnapshot(
        boolean writable,
        boolean memoryOverridden,
        boolean automaticMemory,
        int maximumMemoryMiB,
        boolean javaOverridden,
        JavaVersionType javaVersionType,
        String customJavaVersion,
        String customJavaPath,
        boolean detectedJavaAvailable,
        boolean jvmOptionsOverridden,
        String jvmOptions,
        boolean runningDirectoryOverridden,
        String runningDirectory) {
    /// Validates values that can be safely represented by the Swing editor.
    public InstanceGameSettingsSnapshot {
        if (maximumMemoryMiB <= 0) {
            throw new IllegalArgumentException("maximumMemoryMiB must be positive");
        }
        Objects.requireNonNull(javaVersionType, "javaVersionType");
        Objects.requireNonNull(customJavaVersion, "customJavaVersion");
        Objects.requireNonNull(customJavaPath, "customJavaPath");
        Objects.requireNonNull(jvmOptions, "jvmOptions");
        Objects.requireNonNull(runningDirectory, "runningDirectory");
    }
}
