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
