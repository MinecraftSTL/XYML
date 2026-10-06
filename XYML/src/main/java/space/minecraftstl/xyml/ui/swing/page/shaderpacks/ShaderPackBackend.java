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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one Minecraft shader runtime whose configuration can select a local shader pack.
@NotNullByDefault
public enum ShaderPackBackend {
    /// Iris or Oculus shader configuration.
    IRIS_OCULUS("Iris/Oculus"),

    /// OptiFine shader configuration.
    OPTIFINE("OptiFine");

    /// Stable third-party display name used in dialogs and diagnostics.
    private final String displayName;

    /// Creates one backend definition.
    ///
    /// @param displayName stable display name
    ShaderPackBackend(String displayName) {
        this.displayName = displayName;
    }

    /// Returns the stable display name.
    ///
    /// @return backend display name
    public String displayName() {
        return displayName;
    }
}
