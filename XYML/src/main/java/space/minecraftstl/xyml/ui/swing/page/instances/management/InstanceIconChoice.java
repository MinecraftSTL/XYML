// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.GameInstanceIconType;

import java.nio.file.Path;
import java.util.Objects;

/// Represents one completed selection from the instance icon dialog.
///
/// The two variants keep built-in identity and custom file paths explicit, so callers never infer
/// custom-image intent from `GameInstanceIconType.DEFAULT`.
@NotNullByDefault
public sealed interface InstanceIconChoice permits InstanceIconChoice.BuiltIn, InstanceIconChoice.Custom {
    /// Selects one of the fourteen user-visible bundled instance icons.
    ///
    /// @param iconType bundled icon type; `DEFAULT` is excluded because it aliases the grass image
    @NotNullByDefault
    record BuiltIn(GameInstanceIconType iconType) implements InstanceIconChoice {
        /// Validates that the selected value has an independent user-visible tile.
        public BuiltIn {
            Objects.requireNonNull(iconType, "iconType");
            if (iconType == GameInstanceIconType.DEFAULT) {
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
