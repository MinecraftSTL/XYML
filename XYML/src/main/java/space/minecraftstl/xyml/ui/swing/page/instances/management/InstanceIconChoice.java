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
import space.minecraftstl.xyml.setting.InstanceIconType;

import java.nio.file.Path;
import java.util.Objects;

/// Represents one completed selection from the instance icon dialog.
///
/// The two variants keep built-in identity and custom file paths explicit, so callers never infer
/// custom-image intent from `InstanceIconType.DEFAULT`.
@NotNullByDefault
public sealed interface InstanceIconChoice permits InstanceIconChoice.BuiltIn, InstanceIconChoice.Custom {
    /// Selects one of the fourteen user-visible bundled instance icons.
    ///
    /// @param iconType bundled icon type; `DEFAULT` is excluded because it aliases the grass image
    @NotNullByDefault
    record BuiltIn(InstanceIconType iconType) implements InstanceIconChoice {
        /// Validates that the selected value has an independent user-visible tile.
        public BuiltIn {
            Objects.requireNonNull(iconType, "iconType");
            if (iconType == InstanceIconType.DEFAULT) {
                throw new IllegalArgumentException("DEFAULT is not an independently selectable icon");
            }
        }
    }

    /// Selects one local image that the repository will copy into the instance directory.
    ///
    /// @param file normalized absolute source image path
    @NotNullByDefault
    record Custom(Path file) implements InstanceIconChoice {
        /// Normalizes the selected source path before it crosses onto the background executor.
        public Custom {
            file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        }
    }
}
