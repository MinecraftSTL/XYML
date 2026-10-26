// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.platform.linux;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies Intel GPU classification from Linux PCI addresses.
@NotNullByDefault
final class LinuxIntelGpuClassifierTest {
    /// The conventional Intel integrated graphics function is classified as integrated.
    @Test
    void classifiesConventionalIntegratedAddress() {
        assertEquals(GraphicsCard.Type.Integrated, LinuxIntelGpuClassifier.classify(0, 0, 2, 0));
    }

    /// An Intel adapter at the device number used by the former heuristic remains discrete.
    @Test
    void rejectsFormerDeviceNumberHeuristic() {
        assertEquals(GraphicsCard.Type.Discrete, LinuxIntelGpuClassifier.classify(0, 0, 20, 0));
    }
}
