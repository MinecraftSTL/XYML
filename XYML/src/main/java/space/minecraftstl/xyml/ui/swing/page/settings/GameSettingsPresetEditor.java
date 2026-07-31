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
import space.minecraftstl.xyml.setting.DefaultIsolationType;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.LauncherVisibility;

import java.util.Objects;

/// Contains the editable persisted fields of one reusable game-settings preset.
///
/// The editor purposefully covers launcher-wide fields that are meaningful without an instance: memory allocation,
/// Java selection, JVM arguments, launcher visibility, and the default isolation strategy. It does not replace
/// instance-only overrides.
///
/// @param id stable identity of the preset to update
/// @param autoMemory whether the launcher calculates memory allocation automatically
/// @param minMemoryMiB optional minimum heap memory in MiB
/// @param maxMemoryMiB optional maximum heap memory in MiB
/// @param javaType Java runtime resolution policy
/// @param customJavaVersion user-entered Java version selector
/// @param customJavaPath user-entered Java executable path
/// @param jvmOptions custom JVM command-line arguments
/// @param noJvmOptions whether generated JVM arguments are disabled
/// @param launcherVisibility launcher behavior after a game process starts
/// @param defaultIsolationType default strategy for isolating newly installed instances
@NotNullByDefault
public record GameSettingsPresetEditor(
        GameSettingsPresetID id,
        boolean autoMemory,
        @Nullable Integer minMemoryMiB,
        @Nullable Integer maxMemoryMiB,
        JavaVersionType javaType,
        String customJavaVersion,
        String customJavaPath,
        String jvmOptions,
        boolean noJvmOptions,
        LauncherVisibility launcherVisibility,
        DefaultIsolationType defaultIsolationType) {
    /// Validates immutable editor values before they are applied to the live settings model.
    public GameSettingsPresetEditor {
        id = Objects.requireNonNull(id, "id");
        javaType = Objects.requireNonNull(javaType, "javaType");
        customJavaVersion = Objects.requireNonNull(customJavaVersion, "customJavaVersion");
        customJavaPath = Objects.requireNonNull(customJavaPath, "customJavaPath");
        jvmOptions = Objects.requireNonNull(jvmOptions, "jvmOptions");
        launcherVisibility = Objects.requireNonNull(launcherVisibility, "launcherVisibility");
        defaultIsolationType = Objects.requireNonNull(defaultIsolationType, "defaultIsolationType");
        validateMemory(minMemoryMiB, maxMemoryMiB, autoMemory);
    }

    /// Validates memory values that are persisted as nullable MiB counts.
    ///
    /// @param minMemoryMiB requested lower heap bound, or null when no bound is configured
    /// @param maxMemoryMiB requested upper heap bound, or null when no bound is configured
    /// @param autoMemory whether automatic allocation makes an upper bound optional
    private static void validateMemory(
            @Nullable Integer minMemoryMiB,
            @Nullable Integer maxMemoryMiB,
            boolean autoMemory) {
        if (minMemoryMiB != null && minMemoryMiB < 0) {
            throw new IllegalArgumentException("Minimum memory must not be negative");
        }
        if (maxMemoryMiB != null && maxMemoryMiB <= 0) {
            throw new IllegalArgumentException("Maximum memory must be positive");
        }
        if (!autoMemory && maxMemoryMiB == null) {
            throw new IllegalArgumentException("Manual memory allocation requires a maximum memory value");
        }
        if (minMemoryMiB != null && maxMemoryMiB != null && minMemoryMiB > maxMemoryMiB) {
            throw new IllegalArgumentException("Minimum memory must not exceed maximum memory");
        }
    }
}
