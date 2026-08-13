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
package space.minecraftstl.xyml.util.platform.linux;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;

/// Classifies Intel graphics adapters from their Linux PCI address.
@NotNullByDefault
final class LinuxIntelGpuClassifier {
    /// Prevents construction of this stateless classifier.
    private LinuxIntelGpuClassifier() {
    }

    /// Classifies the conventional Intel integrated graphics PCI address as integrated.
    ///
    /// All other Intel PCI functions are treated as discrete adapters. This matches fastfetch's
    /// Linux GPU detection and avoids classifying every device numbered `0x14` as integrated.
    ///
    /// @param domain PCI domain number
    /// @param bus PCI bus number
    /// @param device PCI device number
    /// @param function PCI function number
    /// @return the graphics adapter type
    static GraphicsCard.Type classify(int domain, int bus, int device, int function) {
        return domain == 0 && bus == 0 && device == 2 && function == 0
                ? GraphicsCard.Type.Integrated
                : GraphicsCard.Type.Discrete;
    }
}
