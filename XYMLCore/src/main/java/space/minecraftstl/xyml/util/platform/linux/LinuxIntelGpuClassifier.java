// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
