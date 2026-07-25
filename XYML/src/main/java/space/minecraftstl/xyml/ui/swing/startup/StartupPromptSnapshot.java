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
package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.OptionalInt;

/// Immutable persisted state used to decide which startup prompts remain relevant.
///
/// @param agreementVersion latest accepted agreement version
/// @param invalidCacheDirectory whether the configured custom cache directory is unusable
/// @param platformPromptVersion latest acknowledged platform prompt version
/// @param deprecatedJavaTipVersion last minimum Java version acknowledged by the user
/// @param interpretedModeSuppressed whether the interpreted-mode warning is permanently suppressed
/// @param softwareRenderingSuppressed whether the software-rendering warning is permanently suppressed
/// @param aprilFoolsShownYear most recent year in which the April Fools invitation was resolved
@NotNullByDefault
public record StartupPromptSnapshot(
        int agreementVersion,
        boolean invalidCacheDirectory,
        int platformPromptVersion,
        OptionalInt deprecatedJavaTipVersion,
        boolean interpretedModeSuppressed,
        boolean softwareRenderingSuppressed,
        OptionalInt aprilFoolsShownYear) {
    /// Validates persisted versions without substituting any missing value.
    public StartupPromptSnapshot {
        Objects.requireNonNull(deprecatedJavaTipVersion, "deprecatedJavaTipVersion");
        Objects.requireNonNull(aprilFoolsShownYear, "aprilFoolsShownYear");
        if (agreementVersion < 0) {
            throw new IllegalArgumentException("agreementVersion must not be negative");
        }
        if (platformPromptVersion < 0) {
            throw new IllegalArgumentException("platformPromptVersion must not be negative");
        }
        if (deprecatedJavaTipVersion.isPresent() && deprecatedJavaTipVersion.getAsInt() < 0) {
            throw new IllegalArgumentException("deprecatedJavaTipVersion must not be negative");
        }
        if (aprilFoolsShownYear.isPresent() && aprilFoolsShownYear.getAsInt() <= 0) {
            throw new IllegalArgumentException("aprilFoolsShownYear must be positive");
        }
    }
}
